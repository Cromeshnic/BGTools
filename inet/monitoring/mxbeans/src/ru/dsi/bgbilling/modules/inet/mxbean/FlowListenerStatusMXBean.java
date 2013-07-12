package ru.dsi.bgbilling.modules.inet.mxbean;

import javax.management.MXBean;

/**
 * MXBean для получения статистики обработчика пакетов netflow
 */
@MXBean
public interface FlowListenerStatusMXBean {
    public int getPacketCountMinute();
}
