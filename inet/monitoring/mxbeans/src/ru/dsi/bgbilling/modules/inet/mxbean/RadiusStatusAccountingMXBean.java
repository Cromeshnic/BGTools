package ru.dsi.bgbilling.modules.inet.mxbean;

import javax.management.MXBean;

/**
 * MXBean для получения статистики радиуса BGInetAccounting
 */
@MXBean
public interface RadiusStatusAccountingMXBean {
    public int getAccountingStartCounter();
    public int getAccountingUpdateCounter();
    public int getAccountingStopCounter();
    public int getAccountingUpdateIgnoreCounter();
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
