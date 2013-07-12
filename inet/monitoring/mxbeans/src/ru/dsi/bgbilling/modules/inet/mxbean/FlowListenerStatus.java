package ru.dsi.bgbilling.modules.inet.mxbean;

import org.apache.log4j.Logger;
import ru.bitel.bgbilling.modules.inet.collector.InetFlowListener;

import javax.management.*;
import java.beans.ConstructorProperties;
import java.lang.management.ManagementFactory;

/**
 * @see ru.dsi.bgbilling.modules.inet.mxbean.FlowListenerStatusMXBean
 */
public class FlowListenerStatus implements FlowListenerStatusMXBean {

    private InetFlowListener flowListener;
    private static final Logger logger = Logger.getLogger(FlowListenerStatus.class);

    @ConstructorProperties({"flowListener"})
    public FlowListenerStatus(InetFlowListener flowListener){
        this.flowListener = flowListener;

        //Регистрируем mxbean
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName mxbeanName = null;
        try {
            mxbeanName = new ObjectName("ru.dsi.bgbilling.modules.inet.mxbean:type=FlowListenerStatus");
            mbs.registerMBean(this, mxbeanName);
        }  catch (InstanceAlreadyExistsException e) {
            logger.error("error on registering MXBean FlowListenerStatus", e);
        } catch (MBeanRegistrationException e) {
            logger.error("error on registering MXBean FlowListenerStatus", e);
        } catch (NotCompliantMBeanException e) {
            logger.error("error on registering MXBean FlowListenerStatus", e);
        } catch (MalformedObjectNameException e) {
            logger.error("error on registering MXBean FlowListenerStatus", e);
        }
    }

    /**
     * @return количество пакетов netflow в минуту
     */
    @Override
    public int getPacketCountMinute() {
        return flowListener.getPacketCountMinute();
    }
}
