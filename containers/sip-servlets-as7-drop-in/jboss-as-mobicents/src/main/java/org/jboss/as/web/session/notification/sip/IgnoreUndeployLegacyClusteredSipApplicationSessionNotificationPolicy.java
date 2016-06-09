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

package org.jboss.as.web.session.notification.sip;

import org.jboss.as.web.session.notification.ClusteredSessionManagementStatus;
import org.jboss.as.web.session.notification.ClusteredSessionNotificationCause;

/**
 * @author jean.deruelle@gmail.com
 *
 */
public class IgnoreUndeployLegacyClusteredSipApplicationSessionNotificationPolicy extends LegacyClusteredSipApplicationSessionNotificationPolicy {
	/**
	 * Overrides superclass to return <code>false</code> if the cause of the
	 * notification is {@link ClusteredSessionNotificationCause.UNDEPLOY}.
	 * 
	 * @return <code>true</code> if <code>status.isLocallyUsed()</code> is
	 *         <code>true</code> and the cause of the notification is not
	 *         {@link ClusteredSessionNotificationCause.UNDEPLOY}.
	 */
	public boolean isSipApplicationSessionAttributeListenerInvocationAllowed(
			ClusteredSessionManagementStatus status,
			ClusteredSessionNotificationCause cause, String attributeName,
			boolean local) {
		return !ClusteredSessionNotificationCause.UNDEPLOY.equals(cause)
				&& super.isSipApplicationSessionAttributeListenerInvocationAllowed(
						status, cause, attributeName, local);
	}

	/**
	 * Overrides superclass to return <code>false</code> if the cause of the
	 * notification is {@link ClusteredSessionNotificationCause.UNDEPLOY}.
	 * 
	 * @return <code>true</code> if <code>status.isLocallyUsed()</code> is
	 *         <code>true</code> and the cause of the notification is not
	 *         {@link ClusteredSessionNotificationCause.UNDEPLOY}.
	 */
	public boolean isSipApplicationSessionListenerInvocationAllowed(
			ClusteredSessionManagementStatus status,
			ClusteredSessionNotificationCause cause, boolean local) {
		return !ClusteredSessionNotificationCause.UNDEPLOY.equals(cause)
				&& super.isSipApplicationSessionListenerInvocationAllowed(status, cause,
						local);
	}
}
