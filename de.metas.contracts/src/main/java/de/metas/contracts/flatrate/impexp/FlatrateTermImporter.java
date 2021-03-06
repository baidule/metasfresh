/**
 * 
 */
package de.metas.contracts.flatrate.impexp;

import java.sql.Timestamp;
import java.util.List;
import java.util.Properties;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.bpartner.service.IBPartnerDAO;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.model.PlainContextAware;
import org.adempiere.util.Services;
import org.adempiere.util.time.SystemTime;
import org.compiere.model.I_AD_User;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Product;
import org.slf4j.Logger;

import com.google.common.collect.Ordering;

import de.metas.adempiere.model.I_C_BPartner_Location;
import de.metas.contracts.IFlatrateBL;
import de.metas.contracts.model.I_C_Flatrate_Term;
import de.metas.contracts.model.I_I_Flatrate_Term;
import de.metas.contracts.model.X_C_Flatrate_Term;
import de.metas.logging.LogManager;
import de.metas.product.IProductBL;
import lombok.NonNull;

/*
 * #%L
 * de.metas.contracts
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/**
 * @author cg
 *
 */
/* package */ class FlatrateTermImporter
{
	public static FlatrateTermImporter newInstance(@NonNull final FlatrateTermImportProcess process)
	{
		return new FlatrateTermImporter(process);
	}

	// services
	private final transient IBPartnerDAO bpartnerDAO = Services.get(IBPartnerDAO.class);
	private final transient IFlatrateBL flatrateBL = Services.get(IFlatrateBL.class);
	
	private static final Logger logger = LogManager.getLogger(FlatrateTermImporter.class);

	final private FlatrateTermImportProcess process;

	private FlatrateTermImporter(FlatrateTermImportProcess process)
	{
		this.process = process;
	}

	private Properties getCtx()
	{
		return process.getCtx();
	}
	public I_C_Flatrate_Term importRecord(final I_I_Flatrate_Term importRecord)
	{
		final I_M_Product product = importRecord.getM_Product();

		final I_C_Flatrate_Term contract = flatrateBL.createTerm(
				PlainContextAware.newWithThreadInheritedTrx(), // context
				importRecord.getC_BPartner(), // bpartner
				importRecord.getC_Flatrate_Conditions(), // conditions
				importRecord.getStartDate(), // startDate
				(I_AD_User)null, // userInCharge
				product, // product
				false // completeIt
		);
		if (contract == null)
		{
			throw new AdempiereException("contract not created");
		}

		setDropShipBPartner(importRecord, contract);
		setDropShipLocation(contract);
		contract.setM_Product(product);
		setUOM(contract, product);
		contract.setPriceActual(importRecord.getPrice());
		setPlannedQtyPerUnit(importRecord, contract);
		setEndDate(importRecord, contract);
		setMasterStartdDate(importRecord, contract);
		setMasterEnddDate(importRecord, contract);
		// important to ended if needed, before saving
		endContractIfNeeded(importRecord, contract);
		InterfaceWrapperHelper.save(contract);
		//
		// Complete the subscription/contract
		if (!isEndedContract(importRecord))
		{
			flatrateBL.complete(contract);
		}
		
		logger.trace("Insert FlaterateTerm - {}", contract);
		
		//
		// Link back the contract to current import record
		importRecord.setC_Flatrate_Term(contract);
		
		return contract;
	}
	
	private void setDropShipBPartner(@NonNull final I_I_Flatrate_Term importRecord, @NonNull final I_C_Flatrate_Term contract)
	{
		final int dropShipBPartnerId = importRecord.getDropShip_BPartner_ID() > 0 ? importRecord.getDropShip_BPartner_ID() : importRecord.getC_BPartner_ID();
		if (dropShipBPartnerId <= 0)
		{
			throw new AdempiereException("DropShip BPartner not found");
		}
		contract.setDropShip_BPartner_ID(dropShipBPartnerId);
	}

	private void setDropShipLocation(@NonNull final I_C_Flatrate_Term contract)
	{
		int dropShipBPartnerId = contract.getDropShip_BPartner_ID();
		final I_C_BPartner_Location dropShipBPLocation = findBPartnerShipToLocation(dropShipBPartnerId);
		if (dropShipBPLocation != null)
		{
			contract.setDropShip_Location(dropShipBPLocation);
		}
	}
	
	private I_C_BPartner_Location findBPartnerShipToLocation(final int bpartnerId)
	{
		final List<I_C_BPartner_Location> bpLocations = bpartnerDAO.retrieveBPartnerLocations(getCtx(), bpartnerId, ITrx.TRXNAME_None);
		if (bpLocations.isEmpty())
		{
			return null;
		}
		else if (bpLocations.size() == 1)
		{
			return bpLocations.get(0);
		}
		else
		{
			final I_C_BPartner_Location bpLocation = bpLocations.stream()
					.filter(I_C_BPartner_Location::isShipTo)
					.sorted(Ordering.natural().onResultOf(bpl -> bpl.isShipToDefault() ? 0 : 1))
					.findFirst().get();
			if (bpLocation.isShipToDefault())
			{
				return bpLocation;
			}
			else
			{
				return null;
			}
		}
	}

	private void setUOM(@NonNull final I_C_Flatrate_Term contract, @NonNull final I_M_Product product)
	{
		final I_C_UOM uom = Services.get(IProductBL.class).getStockingUOM(product);
		contract.setC_UOM(uom);
	}

	private void setPlannedQtyPerUnit(@NonNull final I_I_Flatrate_Term importRecord, @NonNull final I_C_Flatrate_Term contract)
	{
		if (importRecord.getQty() != null && importRecord.getQty().intValue() > 0)
		{
			contract.setPlannedQtyPerUnit(importRecord.getQty());
		}
	}

	private void setEndDate(@NonNull final I_I_Flatrate_Term importRecord, @NonNull final I_C_Flatrate_Term contract)
	{
		if (importRecord.getEndDate() != null)
		{
			contract.setEndDate(importRecord.getEndDate());
		}
	}

	private void setMasterStartdDate(@NonNull final I_I_Flatrate_Term importRecord, @NonNull final I_C_Flatrate_Term contract)
	{
		if (importRecord.getMasterStartDate() != null)
		{
			contract.setMasterStartDate(importRecord.getMasterStartDate());
		}
	}

	private void setMasterEnddDate(@NonNull final I_I_Flatrate_Term importRecord, @NonNull final I_C_Flatrate_Term contract)
	{
		if (importRecord.getMasterEndDate() != null)
		{
			contract.setMasterEndDate(importRecord.getMasterEndDate());
		}
	}

	private boolean isEndedContract(@NonNull final I_I_Flatrate_Term importRecord)
	{
		final Timestamp contractEndDate = importRecord.getEndDate();
		final Timestamp today = SystemTime.asDayTimestamp();
		if (contractEndDate != null && today.after(contractEndDate))
		{
			return true;
		}
		return false;
	}

	private void endContractIfNeeded(@NonNull final I_I_Flatrate_Term importRecord, @NonNull final I_C_Flatrate_Term contract)
	{
		if (isEndedContract(importRecord))
		{
			contract.setContractStatus(X_C_Flatrate_Term.CONTRACTSTATUS_Quit);
			contract.setNoticeDate(contract.getEndDate());
			contract.setIsAutoRenew(false);
			contract.setProcessed(true);
			contract.setDocAction(X_C_Flatrate_Term.DOCACTION_None);
			contract.setDocStatus(X_C_Flatrate_Term.DOCSTATUS_Completed);
		}
	}
}
