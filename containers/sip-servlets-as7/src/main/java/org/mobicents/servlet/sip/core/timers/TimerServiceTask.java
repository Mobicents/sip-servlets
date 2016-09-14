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

package org.mobicents.servlet.sip.core.timers;

import java.io.Serializable;
import java.util.concurrent.ScheduledFuture;

import javax.servlet.sip.SipApplicationSession;

import org.apache.log4j.Logger;
//import org.jboss.web.tomcat.service.session.ClusteredSipManager;
import org.jboss.as.web.session.sip.ClusteredSipSessionManager;
//import org.jboss.web.tomcat.service.session.ClusteredSipServletTimerService;
import org.jboss.as.web.session.sip.ClusteredSipServletTimerService;
import org.jboss.as.clustering.web.OutgoingDistributableSessionData;
import org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession;
//import org.mobicents.servlet.sip.core.session.SipManager;
import org.mobicents.servlet.sip.core.SipManager;
import org.mobicents.servlet.sip.startup.SipStandardContext;
import org.restcomm.timers.PeriodicScheduleStrategy;
import org.restcomm.timers.TimerTask;

/**
 * Sip ServletTimer implementation that can be failed over
 * 
 * @author jean.deruelle@gmail.com
 * @author andras.kokuti@ext.alerant.hu
 *
 */
public class TimerServiceTask extends TimerTask implements MobicentsServletTimer {
	private static Logger logger = Logger.getLogger(TimerServiceTask.class);
	ServletTimerImpl servletTimer;	
	TimerServiceTaskData data;
	SipManager sipManager;
	
	/**
	 * @param data
	 */
	public TimerServiceTask(ClusteredSipSessionManager<? extends OutgoingDistributableSessionData> sipManager, ServletTimerImpl servletTimerImpl, TimerServiceTaskData data) {
		super(data);
		this.data = data;
		this.sipManager = sipManager;
		if(servletTimerImpl == null) {
			MobicentsSipApplicationSession sipApplicationSession = sipManager.getSipApplicationSession(data.getKey(), false);
			if(sipApplicationSession != null) {
				if(logger.isDebugEnabled()) {				
					logger.debug("sip application session for key " + data.getKey() + " was found");
				} 
				PeriodicScheduleStrategy periodicScheduleStrategy = data.getPeriodicScheduleStrategy();
				boolean fixedDelay = false;				
				if(periodicScheduleStrategy != null) {
					if(periodicScheduleStrategy == PeriodicScheduleStrategy.withFixedDelay) {
						fixedDelay = true;
					}
					servletTimerImpl = new ServletTimerImpl(data.getData(), data.getDelay(), fixedDelay, data.getPeriod(), sipApplicationSession.getSipContext().getListeners().getTimerListener(), sipApplicationSession);
				} else {
					servletTimerImpl = new ServletTimerImpl(data.getData(), data.getDelay(), sipApplicationSession.getSipContext().getListeners().getTimerListener(), sipApplicationSession);
				}
				this.servletTimer = new ServletTimerImpl(data.getData(), data.getDelay(), sipApplicationSession.getSipContext().getListeners().getTimerListener(), sipApplicationSession);
				if(logger.isDebugEnabled()) {				
					logger.debug("ServletTimer recreated for TimerServiceTask " + data.getTaskID() + " with ServletTimerId " + servletTimer.getId());
				} 	
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("sip application session for key " + data.getKey() + " was not found neither locally or in the cache, sip servlet timer recreation will be problematic");
				}
			}
		} else {
			this.servletTimer = servletTimerImpl;
			data.setData(servletTimerImpl.getInfo());
			data.setDelay(servletTimerImpl.getDelay());
			data.setKey(((MobicentsSipApplicationSession)servletTimerImpl.getApplicationSession()).getKey());
		}
	}

	/* (non-Javadoc)
	 * @see org.mobicents.timers.TimerTask#run()
	 */
	@Override
	public void runTask() {	
		if(getApplicationSession() == null) {
			servletTimer.setApplicationSession(sipManager.getSipApplicationSession(data.getKey(), false));
		}
		servletTimer.run();
		if(servletTimer.getPeriod() > 0) {
			data.setDelay(getTimeRemaining());
		}
	}

	public void cancel() {
		if(servletTimer != null && !servletTimer.isCanceled()) {
			if(logger.isDebugEnabled()) {				
				logger.debug("Cancelling TimerServiceTask " + data.getTaskID() + " for servletTimerId " + servletTimer.getId());
			} 	
			((ClusteredSipServletTimerService)((SipStandardContext)((org.apache.catalina.Manager)sipManager).getContainer()).getTimerService()).cancel(servletTimer.getId());
			servletTimer.cancel();
		} 
	}
	
	public void cancel(boolean mayInterruptIfRunning, boolean updateAppSessionReadyToInvalidateState) {
		servletTimer.cancel(mayInterruptIfRunning, updateAppSessionReadyToInvalidateState);
	}

	public SipApplicationSession getApplicationSession() {				
		return servletTimer.getApplicationSession();
	}

	@Override
	protected void setScheduledFuture(ScheduledFuture<?> scheduledFuture) {
		servletTimer.setFuture(scheduledFuture);
		super.setScheduledFuture(scheduledFuture);
	}
	
	public String getId() {
		return data.getTaskID().toString();
	}

	public Serializable getInfo() { 
		return data.getData();
	}

	public long getTimeRemaining() {		
		return servletTimer.getTimeRemaining();
	}

	public long scheduledExecutionTime() {
		return servletTimer.scheduledExecutionTime();
	}	
	
	public void passivate() {
		servletTimer.setApplicationSession(null);
	}
} 