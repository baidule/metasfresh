package org.adempiere.inout.util;

import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

/*
 * #%L
 * de.metas.swat.base
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/**
 * Note that we exclude {@link #getLines()} from {@link #toString()}, {@link #equals(Object)} and {@link #hashCode()} to avoid a {@link StackOverflowError}.
 * 
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@Data
@Builder
@EqualsAndHashCode(exclude = "lines")
@ToString(exclude = "lines")
public class DeliveryGroupCandidate
{
	/**
	 * A more generic replacement for orderId..needed at least for deliveryRule complete-order
	 */
	@NonNull
	private final Integer groupId;

	@NonNull
	private final String bPartnerAddress;

	@NonNull
	private final Integer warehouseId;

	@NonNull
	private final Integer shipperId;

	@Default
	private final List<DeliveryLineCandidate> lines = new ArrayList<>();
}
