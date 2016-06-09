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

/*
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

import java.util.HashMap;
import java.util.Map;

import org.jboss.as.clustering.web.OutgoingSessionGranularitySessionData;
import org.jboss.as.clustering.web.sip.DistributableSipApplicationSessionMetadata;
import org.mobicents.servlet.sip.core.session.SipApplicationSessionKey;
import org.mobicents.servlet.sip.core.SipContext;

/**
 * Implementation of a clustered sip application session for the JBossCacheManager.
 * This class is based on the following Jboss class org.jboss.web.tomcat.service.session.SessionBasedClusteredSession JBOSS AS 5.1.0.GA Tag
 *
 * The replication granularity
 * level is session based; that is, we replicate per whole session object.
 *
 * <p/>
 * Note that the isolation level of the cache dictates the
 * concurrency behavior.</p>
 *
 * @author Ben Wang
 * @author Brian Stansberry
 * 
 *
 * @author <A HREF="mailto:jean.deruelle@gmail.com">Jean Deruelle</A>
 * 
 */
public class SessionBasedClusteredSipApplicationSession extends ClusteredSipApplicationSession<OutgoingSessionGranularitySessionData> {

	/**
	 * Descriptive information describing this Session implementation.
	 */
	protected static final String info = "SessionBasedClusteredSipApplicationSession/1.0";

	/**
	 * @param key
	 * @param sipFactoryImpl
	 * @param mobicentsSipApplicationSession
	 */
	public SessionBasedClusteredSipApplicationSession(SipApplicationSessionKey key,
			SipContext sipContext, boolean useJK) {
		super(key, sipContext, useJK);
	}

	// ---------------------------------------------- Overridden Public Methods

	@Override
	public String getInfo() {
		return (info);
	}

	@Override
	protected OutgoingSessionGranularitySessionData getOutgoingSipApplicationSessionData() {
		Map<String, Object> attrs = isSessionAttributeMapDirty() ? getSessionAttributeMap() : null;
		DistributableSipApplicationSessionMetadata metadata = isSessionMetadataDirty() ? (DistributableSipApplicationSessionMetadata)getSessionMetadata() : null;
		
		Long timestamp = attrs != null || metadata != null
				|| getMustReplicateTimestamp() ? Long
				.valueOf(getSessionTimestamp()) : null;
		OutgoingData outgoingData = new OutgoingData(null, getVersion(), timestamp, key.getId(), metadata,
				attrs);
		outgoingData.setSessionMetaDataDirty(isSessionMetadataDirty());
		return outgoingData;
	}

	@Override
	protected Object removeAttributeInternal(String name, boolean localCall,
			boolean localOnly) {
		if (localCall)
			sessionAttributesDirty();
		return getAttributesInternal().remove(name);
	}

	@Override
	protected Object setAttributeInternal(String name, Object value) {
		sessionAttributesDirty();
		return getAttributesInternal().put(name, value);
	}

	// ----------------------------------------------------------------- Private

	private Map<String, Object> getSessionAttributeMap() {
		Map<String, Object> attrs = new HashMap<String, Object>(
				getAttributesInternal());
		removeExcludedAttributes(attrs);
		return attrs;
	}

	// ----------------------------------------------------------------- Classes

	private static class OutgoingData extends
			OutgoingDistributableSipApplicationSessionDataImpl implements
			OutgoingSessionGranularitySessionData {
		private final Map<String, Object> attributes;

		public OutgoingData(String realId, int version, Long timestamp, String key,
				DistributableSipApplicationSessionMetadata metadata,
				Map<String, Object> attributes) {
			super(realId, version, timestamp, key, metadata);
			this.attributes = attributes;
		}

		public Map<String, Object> getSessionAttributes() {
			return attributes;
		}
	}
}