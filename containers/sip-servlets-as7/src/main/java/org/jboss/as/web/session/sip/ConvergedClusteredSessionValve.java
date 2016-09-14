/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package org.jboss.as.web.session.sip;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;

import org.apache.catalina.Manager;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.jboss.logging.Logger;
import org.jboss.servlet.http.HttpEvent;
import org.jboss.as.web.session.ClusteredSession;
import org.jboss.as.web.session.ClusteredSessionValve;
import org.jboss.as.web.session.SnapshotManager;
import org.jboss.as.clustering.web.BatchingManager;
import org.jboss.as.clustering.web.OutgoingDistributableSessionData;

/**
 * This class extends the jboss ClusteredSessionValve (JBoss AS 5.1.0.GA Tag) to
 * make use of the ConvergedSessionReplicationContext.
 * 
 * @author <A HREF="mailto:jean.deruelle@gmail.com">Jean Deruelle</A>
 * @author posfai.gergely@ext.alerant.hu
 * 
 */
public class ConvergedClusteredSessionValve extends ClusteredSessionValve {

	// The info string for this Valve
	private static final String info = "ConvergedClusteredSessionValve/1.0";
	private final BatchingManager tm;
	private final Manager manager;
	protected static Logger logger = Logger.getLogger(ConvergedClusteredSessionValve.class);
	/**
	 * Create a new Valve.
	 */
	public ConvergedClusteredSessionValve(Manager manager, BatchingManager tm) {
		super(manager, tm);
		this.tm = tm;
		this.manager = manager;
	}

	/**
	 * Get information about this Valve.
	 */
	public String getInfo() {
		return info;
	}

	/**
	 * Valve-chain handler method. This method gets called when the request goes
	 * through the Valve-chain. Our session replication mechanism replicates the
	 * session after request got through the servlet code.
	 * 
	 * @param request
	 *            The request object associated with this request.
	 * @param response
	 *            The response object associated with this request.
	 */
	public void invoke(Request request, Response response) throws IOException,
			ServletException {
		handleRequest(request, response, null, false);
	}

	/**
	 * Valve-chain handler method. This method gets called when the request goes
	 * through the Valve-chain. Our session replication mechanism replicates the
	 * session after request got through the servlet code.
	 * 
	 * @param request
	 *            The request object associated with this request.
	 * @param response
	 *            The response object associated with this request.
	 */
	public void event(Request request, Response response, HttpEvent event)
			throws IOException, ServletException {
		handleRequest(request, response, event, true);
	}

	private void handleRequest(Request request, Response response,
			HttpEvent event, boolean isEvent) throws IOException,
			ServletException {
		// Initialize the context and store the request and response objects
		// for any clustering code that has no direct access to these objects
		ConvergedSessionReplicationContext.enterWebapp(request, response, true);
		ConvergedSessionReplicationContext.enterSipapp(null, null, true);

		boolean startedBatch = startBatchTransaction();
		try {
			// Workaround to JBAS-5735. Ensure we get the session from the
			// manager
			// rather than a cached ref from the Request.
			String requestedId = request.getRequestedSessionId();
			if (requestedId != null) {
				manager.findSession(requestedId);
			}

			// let the servlet invocation go through
			if (isEvent) {
				getNext().event(request, response, event);
			} else {
				getNext().invoke(request, response);
			}
		} finally // We replicate no matter what
		{
			// --> We are now after the servlet invocation
			try {
				ConvergedSessionReplicationContext ctx = ConvergedSessionReplicationContext
						.exitWebapp();

				if (ctx.getSoleSnapshotManager() != null) {
					ctx.getSoleSnapshotManager().snapshot(ctx.getSoleSession());
				} else {
					// Cross-context request touched multiple sesssions;
					// need to replicate them all
					handleCrossContextSessions(ctx);
				}
				ctx = ConvergedSessionReplicationContext.exitSipapp();
				final SnapshotSipManager snapshotSipManager = ctx.getSoleSnapshotSipManager();
				if(logger.isDebugEnabled()) {
					logger.debug("Snapshot Manager " + snapshotSipManager);
				}
				if (snapshotSipManager != null) {
					Set<ClusteredSipSession<? extends OutgoingDistributableSessionData>> sipSessions = ctx.getSipSessions();
					for (ClusteredSipSession<? extends OutgoingDistributableSessionData> clusteredSipSession : sipSessions) {
						snapshotSipManager.snapshot(clusteredSipSession);
					}
					Set<ClusteredSipApplicationSession<? extends OutgoingDistributableSessionData>> sipApplicationSessions = ctx.getSipApplicationSessions();
					for (ClusteredSipApplicationSession<? extends OutgoingDistributableSessionData> clusteredSipApplicationSession : sipApplicationSessions) {
						snapshotSipManager.snapshot(clusteredSipApplicationSession);
					}
				} 
			} finally {
				if (startedBatch) {
					tm.endBatch();
				}
			}

		}
	}

	private boolean startBatchTransaction() throws ServletException {
		boolean started = false;
		try {
			if (this.tm != null && this.tm.isBatchInProgress() == false) {
				this.tm.startBatch();
				started = true;
			}
		} catch (RuntimeException re) {
			throw re;
		} catch (Exception e) {
			throw new ServletException(
					"Failed to initiate batch replication transaction", e);
		}

		return started;
	}

	@SuppressWarnings("unchecked")
	private void handleCrossContextSessions(ConvergedSessionReplicationContext ctx) {
		// Genericized code below crashes some Sun JDK compilers; see
		// http://www.jboss.org/index.html?module=bb&op=viewtopic&t=154175

		// Map<ClusteredSession<? extends OutgoingDistributableSessionData>,
		// SnapshotManager> sessions = ctx.getCrossContextSessions();
		// if (sessions != null && sessions.size() > 0)
		// {
		// for (Map.Entry<ClusteredSession<? extends
		// OutgoingDistributableSessionData>, SnapshotManager> entry :
		// sessions.entrySet())
		// {
		// entry.getValue().snapshot(entry.getKey());
		// }
		// }

		// So, use this non-genericized code instead
		Map sessions = ctx.getCrossContextSessions();
		if (sessions != null && sessions.size() > 0) {
			for (Iterator it = sessions.entrySet().iterator(); it.hasNext();) {
				Map.Entry entry = (Map.Entry) it.next();
				((SnapshotManager) entry.getValue())
						.snapshot((ClusteredSession) entry.getKey());
			}
		}
	}
}
