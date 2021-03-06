/*
 * Copyright (c) 2010, SQL Power Group Inc.
 *
 * This file is part of SQL Power Library.
 *
 * SQL Power Library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * SQL Power Library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.object;

import java.util.Comparator;

/**
 * This {@link Comparator} compares {@link SPObject}s by their name property.
 * Null {@link SPObject}s are allowed and come before non-null {@link SPObject}s.
 * Also, null names come before non-null names.
 */
public class SPObjectNameComparator implements Comparator<SPObject> {

	public int compare(SPObject spo1, SPObject spo2) {
		if (spo1 == spo2) {
			return 0;
		} else if (spo1 == null) {
			return -1;
		} else if (spo2 == null) {
			return 1;
		} else if (spo1.getName() == null && spo2.getName() == null) {
			return 0;
		} else if (spo1.getName() == null) {
			return 1;
		} else if (spo2.getName() == null) {
			return -1;
		} else {
			return spo1.getName().compareTo(spo2.getName());
		}
	}

}
