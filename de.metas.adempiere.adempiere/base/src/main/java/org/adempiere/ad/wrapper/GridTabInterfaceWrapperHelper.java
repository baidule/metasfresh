package org.adempiere.ad.wrapper;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.GridTabWrapper;
import org.compiere.model.GridField;
import org.compiere.model.GridTab;
import org.slf4j.Logger;

import de.metas.logging.LogManager;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2016 metas GmbH
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

public class GridTabInterfaceWrapperHelper implements IInterfaceWrapperHelper
{
	private static final Logger logger = LogManager.getLogger(GridTabInterfaceWrapperHelper.class);

	@Override
	public boolean canHandled(final Object model)
	{
		return GridTabWrapper.isHandled(model);
	}

	@Override
	@SuppressWarnings("deprecation")
	public <T> T wrap(final Object model, final Class<T> cl, final boolean useOldValues)
	{
		if (useOldValues)
		{
			return GridTabWrapper.create(model, cl, true);
		}
		else
		{
			// preserve "old values" flag
			return GridTabWrapper.create(model, cl);
		}

	}

	@Override
	public void refresh(final Object model, final boolean discardChanges)
	{
		GridTabWrapper.refresh(model);
	}

	@Override
	public void refresh(final Object model, final String trxName)
	{
		GridTabWrapper.refresh(model);
	}

	@Override
	public boolean hasModelColumnName(final Object model, final String columnName)
	{
		return GridTabWrapper.hasColumnName(model, columnName);
	}

	@Override
	public boolean setValue(final Object model, final String columnName, final Object value, final boolean throwExIfColumnNotFound)
	{
		final GridTab gridTab = GridTabWrapper.getGridTab(model);
		final GridField gridField = gridTab.getField(columnName);
		if (gridField == null)
		{
			final AdempiereException ex = new AdempiereException("No field with ColumnName=" + columnName + " found in " + gridTab + " for " + model);
			if (throwExIfColumnNotFound)
			{
				throw ex;
			}
			else
			{
				logger.warn(ex.getLocalizedMessage(), ex);
				return false;
			}
		}

		gridTab.setValue(gridField, value);
		return true;
	}

}
