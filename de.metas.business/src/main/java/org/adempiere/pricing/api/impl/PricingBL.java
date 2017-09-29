package org.adempiere.pricing.api.impl;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.pricing.api.IEditablePricingContext;
import org.adempiere.pricing.api.IPriceListBL;
import org.adempiere.pricing.api.IPriceListDAO;
import org.adempiere.pricing.api.IPricingBL;
import org.adempiere.pricing.api.IPricingContext;
import org.adempiere.pricing.api.IPricingDAO;
import org.adempiere.pricing.api.IPricingResult;
import org.adempiere.pricing.exceptions.PriceListVersionNotFoundException;
import org.adempiere.pricing.model.I_C_PricingRule;
import org.adempiere.pricing.spi.AggregatedPricingRule;
import org.adempiere.pricing.spi.IPricingRule;
import org.adempiere.uom.api.IUOMConversionBL;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.adempiere.util.proxy.Cached;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_PriceList;
import org.compiere.model.I_M_PriceList_Version;
import org.compiere.model.I_M_Product;
import org.compiere.model.I_M_ProductPrice;
import org.compiere.util.Env;
import org.compiere.util.Util;
import org.slf4j.Logger;

import de.metas.adempiere.model.I_C_InvoiceLine;
import de.metas.adempiere.util.CacheCtx;
import de.metas.logging.LogManager;
import de.metas.pricing.ProductPrices;

public class PricingBL implements IPricingBL
{
	private final Logger logger = LogManager.getLogger(getClass());

	public static final PricingBL instance = new PricingBL();

	public PricingBL()
	{
	}

	@Override
	public IEditablePricingContext createPricingContext()
	{
		return new PricingContext();
	}

	@Override
	public IEditablePricingContext createInitialContext(int M_Product_ID, int C_BPartner_ID, int C_UOM_ID, BigDecimal Qty, boolean isSOTrx)
	{
		final IEditablePricingContext pricingCtx = createPricingContext();
		pricingCtx.setM_Product_ID(M_Product_ID);
		pricingCtx.setC_BPartner_ID(C_BPartner_ID);
		pricingCtx.setConvertPriceToContextUOM(true); // backward compatibility

		if (Qty != null && Qty.signum() != 0)
		{
			pricingCtx.setQty(Qty);
		}
		else
		{
			pricingCtx.setQty(BigDecimal.ONE);
		}
		pricingCtx.setSOTrx(isSOTrx);
		pricingCtx.setC_UOM_ID(C_UOM_ID);

		return pricingCtx;
	}

	@Override
	public IPricingResult calculatePrice(final IPricingContext pricingCtx)
	{
		final Properties ctx = Env.getCtx();

		final IPricingContext pricingCtxToUse = setupPricingContext(ctx, pricingCtx);
		final IPricingResult result = createInitialResult(pricingCtxToUse);

		// task 08908 do not change anything if the price is manual
		Boolean isManualPrice = pricingCtxToUse.isManualPrice();

		// if the manualPrice value was not set in the pricing context, take it from the reference object
		if (isManualPrice == null)
		{
			final Object referenceObject = pricingCtxToUse.getReferencedObject();

			if (referenceObject != null)
			{
				isManualPrice = InterfaceWrapperHelper.getValueOrNull(referenceObject, I_C_InvoiceLine.COLUMNNAME_IsManualPrice);
			}
		}

		// if the isManualPrice is not even a column of the reference object, consider it false
		final boolean isManualPriceToUse = isManualPrice == null ? false : isManualPrice;

		if (isManualPriceToUse)
		{
			// task 08908: returning the result is not reliable enough because we are not sure the values
			// in the initial result are the ones from the reference object.
			// TODO: a new pricing rule for manual prices (if needed)
			// Keeping the fine log anyway
			logger.debug("The pricing engine doesn't have to calculate the price because it was already manually set in the priocing context: {}.", pricingCtxToUse);

			// return result;
		}

		final AggregatedPricingRule aggregatedPricingRule = getAggregatedPricingRule(ctx);

		aggregatedPricingRule.calculate(pricingCtxToUse, result);

		//
		// After calculation

		// First we check if we have different UOM in the context and result
		adjustPriceByUOM(pricingCtxToUse, result);
		setPrecision(pricingCtxToUse, result);

		if (logger.isDebugEnabled())
		{
			logger.debug("calculatePrice (final context): {}", pricingCtxToUse);
			logger.debug("calculatePrice (result): {}", result);
		}

		return result;
	}

	/**
	 * Set various fields in context, before using it.
	 *
	 * @param ctx
	 * @param pricingCtx
	 * @return configured pricing context (to be used in pricing calculations)
	 */
	private IPricingContext setupPricingContext(final Properties ctx, final IPricingContext pricingCtx)
	{
		final IPriceListDAO priceListDAO = Services.get(IPriceListDAO.class);

		final IEditablePricingContext pricingCtxToUse = pricingCtx.copy();

		final Timestamp priceDate = pricingCtxToUse.getPriceDate();

		//
		// Set M_PriceList_ID and M_PriceList_Version_ID from pricingSystem, date and country, if necessary;
		// if set and there is one in the pricingCtx, also check if it is consistent.
		if (pricingCtxToUse.getM_PricingSystem_ID() > 0
				&& priceDate != null
				&& pricingCtxToUse.getM_Product_ID() > 0
				&& pricingCtxToUse.getC_Country_ID() > 0)
		{
			final IPriceListBL priceListBL = Services.get(IPriceListBL.class);
			final I_M_PriceList_Version computedPLV = priceListBL.getCurrentPriceListVersionOrNull(
					pricingCtx.getM_PricingSystem(),
					pricingCtx.getC_Country(),
					pricingCtxToUse.getPriceDate(),
					pricingCtx.isSOTrx(),
					null);

			if (computedPLV != null)
			{
				final int priceListId = computedPLV.getM_PriceList_ID();
				pricingCtxToUse.setM_PriceList_ID(priceListId);

				// while we are at it, do a little sanity check and also set the PLV-ID
				Check.assume(pricingCtxToUse.getM_PriceList_Version_ID() <= 0 || pricingCtxToUse.getM_PriceList_Version_ID() == computedPLV.getM_PriceList_Version_ID(),
						"Given PricingContext {} has M_PriceList_Version={}, but from M_PricingSystem={}, Product={}, Country={} and IsSOTrx={}, we computed a different M_PriceList_Version={}",
						pricingCtxToUse,  // 0
						pricingCtxToUse.getM_PriceList_Version(),  // 1
						pricingCtxToUse.getM_PricingSystem(),  // 2
						pricingCtx.getM_Product(),  // 3
						pricingCtx.getC_Country(),  // 4
						pricingCtx.isSOTrx(),  // 5
						computedPLV);
				pricingCtxToUse.setM_PriceList_Version_ID(computedPLV.getM_PriceList_Version_ID());
			}
		}

		//
		// Set M_PriceList_Version_ID from PL and date, if necessary.
		if (pricingCtxToUse.getM_PriceList_Version_ID() <= 0
				&& pricingCtxToUse.getM_PriceList_ID() > 0
				&& priceDate != null)
		{
			final I_M_PriceList priceList = priceListDAO.retrievePriceList(ctx, pricingCtx.getM_PriceList_ID());
			try
			{
				final Boolean processedPLVFiltering = null; // task 09533: the user doesn't know about PLV's processed flag, so we can't filter by it
				final I_M_PriceList_Version plv = priceListDAO.retrievePriceListVersionOrNull(priceList, priceDate, processedPLVFiltering);
				if (plv != null)
				{
					final int priceListVersionId = plv.getM_PriceList_Version_ID();
					logger.debug("Setting to context: M_PriceList_Version_ID={} from M_PriceList={} and PriceDate={}", priceListVersionId, priceList, priceDate);
					pricingCtxToUse.setM_PriceList_Version_ID(priceListVersionId);
				}
			}
			catch (PriceListVersionNotFoundException e)
			{
				// NOTE: don't fail here because it could be a valid case and some particular pricing rules can handle it.
				// NOTE2: also pls keep in mind that if we would fail here the whole pricing calculation would fail.
				logger.info("Skip setting pricing context's price list version because it was not found", e);
			}
		}

		//
		// set PL from PLV
		if (pricingCtxToUse.getM_PriceList_ID() <= 0
				&& pricingCtxToUse.getM_PriceList_Version_ID() > 0)
		{
			final I_M_PriceList_Version priceListVersion = pricingCtx.getM_PriceList_Version();

			logger.info("Setting to context: M_PriceList_ID={} from M_PriceList_Version={}", priceListVersion.getM_PriceList_ID(), priceListVersion);
			pricingCtxToUse.setM_PriceList_ID(priceListVersion.getM_PriceList_ID());
		}

		//
		// set priceDate from PLV
		if (pricingCtxToUse.getPriceDate() == null
				&& pricingCtxToUse.getM_PriceList_Version_ID() > 0)
		{
			final I_M_PriceList_Version priceListVersion = pricingCtx.getM_PriceList_Version();

			logger.info("Setting to context: PriceDate={} from M_PriceList_Version={}", priceListVersion.getValidFrom(), priceListVersion);
			pricingCtxToUse.setPriceDate(priceListVersion.getValidFrom());
		}

		return pricingCtxToUse;
	}

	private void setPrecision(IPricingContext pricingCtx, IPricingResult result)
	{
		if (pricingCtx.getM_PriceList_ID() > 0 && result.getPrecision() == IPricingResult.NO_PRECISION)
		{
			final int precision = getPricePrecision(pricingCtx.getCtx(), pricingCtx.getM_PriceList_ID());
			if (precision >= 0)
			{
				result.setPrecision(precision);
			}
		}
	}

	private void adjustPriceByUOM(IPricingContext pricingCtx, IPricingResult result)
	{
		// We are asked to keep the prices in context's UOM, so do nothing
		if (!pricingCtx.isConvertPriceToContextUOM())
		{
			return;
		}

		if ((pricingCtx.getC_UOM_ID() > 0) && (pricingCtx.getC_UOM_ID() != result.getPrice_UOM_ID()))
		{
			final I_C_UOM uomTo = InterfaceWrapperHelper.create(Env.getCtx(), pricingCtx.getC_UOM_ID(), I_C_UOM.class, ITrx.TRXNAME_None);
			final I_C_UOM uomFrom = InterfaceWrapperHelper.create(Env.getCtx(), result.getPrice_UOM_ID(), I_C_UOM.class, ITrx.TRXNAME_None);
			final I_M_Product product = InterfaceWrapperHelper.create(Env.getCtx(), result.getM_Product_ID(), I_M_Product.class, ITrx.TRXNAME_None);

			final BigDecimal factor = Services.get(IUOMConversionBL.class).convertQty(product, BigDecimal.ONE, uomFrom, uomTo);
			Check.assumeNotNull(factor, "Conversion error");

			result.setPriceLimit(factor.multiply(result.getPriceLimit()));
			result.setPriceList(factor.multiply(result.getPriceList()));
			result.setPriceStd(factor.multiply(result.getPriceStd()));
			result.setPrice_UOM_ID(pricingCtx.getC_UOM_ID());
		}
	}

	@Cached(cacheName = I_M_Product.Table_Name)
	/* package */I_M_Product getM_Product(@CacheCtx final Properties ctx, final int productId)
	{
		return InterfaceWrapperHelper.create(ctx, productId, I_M_Product.class, ITrx.TRXNAME_None);
	}

	private void setProductInfo(final IPricingContext pricingCtx, final IPricingResult result)
	{
		final I_M_Product product = pricingCtx.getM_Product();
		if (product == null || product.getM_Product_ID() <= 0)
		{
			return;
		}

		result.setM_Product_Category_ID(product.getM_Product_Category_ID());

		//
		// Set Price_UOM_ID (06942)
		final I_M_PriceList_Version plv = pricingCtx.getM_PriceList_Version();
		if (plv != null)
		{
			final I_M_ProductPrice productPrice = ProductPrices.retrieveMainProductPriceOrNull(plv, product.getM_Product_ID());
			if (productPrice == null)
			{
				result.setPrice_UOM_ID(product.getC_UOM_ID());
			}
			else
			{
				result.setPrice_UOM_ID(productPrice.getC_UOM_ID());
			}
		}
		else
		{
			result.setPrice_UOM_ID(product.getC_UOM_ID());
		}
	}

	@Override
	public IPricingResult createInitialResult(final IPricingContext pricingCtx)
	{
		final PricingResult result = new PricingResult();
		result.setM_PricingSystem_ID(pricingCtx.getM_PricingSystem_ID());
		result.setM_PriceList_ID(pricingCtx.getM_PriceList_ID());
		result.setM_PriceList_Version_ID(pricingCtx.getM_PriceList_Version_ID());
		result.setM_Product_ID(pricingCtx.getM_Product_ID());
		result.setC_Currency_ID(pricingCtx.getC_Currency_ID());
		result.setDisallowDiscount(pricingCtx.isDisallowDiscount());
		result.setPriceDate(pricingCtx.getPriceDate());

		result.setCalculated(false);

		setProductInfo(pricingCtx, result);

		return result;
	}

	@Override
	@Cached(cacheName = I_C_PricingRule.Table_Name + "_AggregatedPricingRule")
	public AggregatedPricingRule getAggregatedPricingRule(@CacheCtx Properties ctx)
	{
		final AggregatedPricingRule aggregatedPricingRule = new AggregatedPricingRule();
		final List<IPricingRule> rules = retrievePricingRules(ctx);
		for (IPricingRule rule : rules)
		{
			aggregatedPricingRule.addPricingRule(rule);
		}

		logger.debug("aggregatedPricingRule: {}", aggregatedPricingRule);
		return aggregatedPricingRule;
	}

	private List<IPricingRule> retrievePricingRules(Properties ctx)
	{
		final List<I_C_PricingRule> rulesDef = Services.get(IPricingDAO.class).retrievePricingRules(ctx);

		final List<IPricingRule> rules = new ArrayList<IPricingRule>(rulesDef.size());
		for (final I_C_PricingRule ruleDef : rulesDef)
		{
			final IPricingRule rule = createPricingRule(ruleDef);
			if (rule == null)
			{
				continue;
			}
			if (rules.contains(rule))
			{
				logger.warn("Rule was already loaded: " + rule + " [SKIP]");
				continue;
			}
			rules.add(rule);
		}

		return rules;
	}

	/**
	 *
	 * @param ruleDef
	 * @return {@link IPricingRule} or null if an error occurred
	 */
	private IPricingRule createPricingRule(I_C_PricingRule ruleDef)
	{
		final String classname = ruleDef.getClassname();
		try
		{
			final IPricingRule rule = Util.getInstance(IPricingRule.class, classname);
			return rule;
		}
		catch (Exception e)
		{
			logger.warn("Cannot load rule for classname " + classname, e);
		}
		return null;
	}

	private final int getPricePrecision(final Properties ctx, final int priceListId)
	{
		Check.assume(priceListId > 0, "priceListId > 0");
		final I_M_PriceList priceList = InterfaceWrapperHelper.create(ctx, priceListId, I_M_PriceList.class, ITrx.TRXNAME_None);

		return priceList.getPricePrecision();
	}
}
