/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.web.session.sip;

import org.jboss.as.web.session.OutgoingDistributableSessionDataImpl;
import org.jboss.as.clustering.web.sip.DistributableSipApplicationSessionMetadata;
import org.jboss.as.clustering.web.sip.OutgoingDistributableSipApplicationSessionData;

/**
 * @author jean.deruelle@gmail.com
 *
 */
public class OutgoingDistributableSipApplicationSessionDataImpl extends OutgoingDistributableSessionDataImpl implements
		OutgoingDistributableSipApplicationSessionData {
	
	String sipApplicationSessionKey;
	private boolean isSessionMetaDataDirty;
	
	public OutgoingDistributableSipApplicationSessionDataImpl(String realId,
			int version, Long timestamp, String key, DistributableSipApplicationSessionMetadata metadata) {
		super(realId, version, timestamp, metadata);
		this.sipApplicationSessionKey = key;
	}

	public String getSipApplicationSessionKey() {
		return this.sipApplicationSessionKey;
	}
	/**
	 * @param isSessionMetaDataDirty the isSessionMetaDataDirty to set
	 */
	public void setSessionMetaDataDirty(boolean isSessionMetaDataDirty) {
		this.isSessionMetaDataDirty = isSessionMetaDataDirty;
	}

	/**
	 * @return the isSessionMetaDataDirty
	 */
	public boolean isSessionMetaDataDirty() {
		return isSessionMetaDataDirty;
	}
}
