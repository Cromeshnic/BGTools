package ru.dsi.bgbilling.modules.inet.mxbean;

import javax.management.MXBean;

/**
 * * MXBean для получения статистики радиуса BGInetAccess
 */
@MXBean
public interface RadiusStatusAccessMXBean {
    public int getAuthenticationAcceptCounter();
    public int getAuthenticationRejectCounter();
    public int getAuthenticationIgnoreCounter();
    public int getAntispamIgnoreCounter();

    public Long getActiveParentConnectionCount();

    public Long getSuspendedParentConnectionCount();

    public Long getClosedParentConnectionCount();

    public Long getFinishedParentConnectionCount();

    public Long getActiveServiceConnectionCount();

    public Long getSuspendedServiceConnectionCount();

    public Long getClosedServiceConnectionCount();

    public Long getFinishedServiceConnectionCount();
}
