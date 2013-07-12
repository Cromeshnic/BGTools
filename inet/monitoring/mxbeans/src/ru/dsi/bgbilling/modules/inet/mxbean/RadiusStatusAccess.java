package ru.dsi.bgbilling.modules.inet.mxbean;

import org.apache.log4j.Logger;
import ru.bitel.bgbilling.kernel.network.radius.RadiusListener;
import ru.bitel.bgbilling.modules.inet.access.Access;
import ru.bitel.bgbilling.modules.inet.access.InetConnectionRuntime;

import javax.management.*;
import java.beans.ConstructorProperties;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

/**
 * @see ru.dsi.bgbilling.modules.inet.mxbean.RadiusStatusAccessMXBean
 */
public class RadiusStatusAccess implements RadiusStatusAccessMXBean {

    private Access access;
    private static final Logger logger = Logger.getLogger(RadiusStatusAccess.class);

    @ConstructorProperties({"access"})
    public RadiusStatusAccess(Access access){
        this.access = access;

        //Регистрируем mxbean
        try {
            MBeanServer mbs =
                    ManagementFactory.getPlatformMBeanServer();
            ObjectName mxbeanName = new ObjectName("ru.dsi.bgbilling.modules.inet.mxbean:type=RadiusStatusAccess");
            mbs.registerMBean(this, mxbeanName);
        } catch (InstanceAlreadyExistsException e) {
            logger.error("error on registering MXBean RadiusStatusAccess", e);
        } catch (MBeanRegistrationException e) {
            logger.error("error on registering MXBean RadiusStatusAccess", e);
        } catch (NotCompliantMBeanException e) {
            logger.error("error on registering MXBean RadiusStatusAccess", e);
        } catch (MalformedObjectNameException e) {
            logger.error("error on registering MXBean RadiusStatusAccess", e);
        }
    }

    @Override
    public Long getActiveParentConnectionCount(){
        long count = 0;
        for (Map.Entry<Integer, List<InetConnectionRuntime>> entry : this.access.connectionManager.inetServEntrySet()) {
            for (InetConnectionRuntime connectionRuntime : entry.getValue()) {
                if(connectionRuntime.connection.getConnectionStatus()==1 && connectionRuntime.connection.getParentConnectionId()<=0L){
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Long getSuspendedParentConnectionCount(){
        long count = 0;
        for (Map.Entry<Integer, List<InetConnectionRuntime>> entry : this.access.connectionManager.inetServEntrySet()) {
            for (InetConnectionRuntime connectionRuntime : entry.getValue()) {
                if(connectionRuntime.connection.getConnectionStatus()==2 && connectionRuntime.connection.getParentConnectionId()<=0L){
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Long getClosedParentConnectionCount(){
        long count = 0;
        for (Map.Entry<Integer, List<InetConnectionRuntime>> entry : this.access.connectionManager.inetServEntrySet()) {
            for (InetConnectionRuntime connectionRuntime : entry.getValue()) {
                if(connectionRuntime.connection.getConnectionStatus()==3 && connectionRuntime.connection.getParentConnectionId()<=0L){
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Long getFinishedParentConnectionCount(){
        long count = 0;
        for (Map.Entry<Integer, List<InetConnectionRuntime>> entry : this.access.connectionManager.inetServEntrySet()) {
            for (InetConnectionRuntime connectionRuntime : entry.getValue()) {
                if(connectionRuntime.connection.getConnectionStatus()==4 && connectionRuntime.connection.getParentConnectionId()<=0L){
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Long getActiveServiceConnectionCount(){
        long count = 0;
        for (Map.Entry<Integer, List<InetConnectionRuntime>> entry : this.access.connectionManager.inetServEntrySet()) {
            for (InetConnectionRuntime connectionRuntime : entry.getValue()) {
                if(connectionRuntime.connection.getConnectionStatus()==1 && connectionRuntime.connection.getParentConnectionId()>0L){
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Long getSuspendedServiceConnectionCount(){
        long count = 0;
        for (Map.Entry<Integer, List<InetConnectionRuntime>> entry : this.access.connectionManager.inetServEntrySet()) {
            for (InetConnectionRuntime connectionRuntime : entry.getValue()) {
                if(connectionRuntime.connection.getConnectionStatus()==2 && connectionRuntime.connection.getParentConnectionId()>0L){
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Long getClosedServiceConnectionCount(){
        long count = 0;
        for (Map.Entry<Integer, List<InetConnectionRuntime>> entry : this.access.connectionManager.inetServEntrySet()) {
            for (InetConnectionRuntime connectionRuntime : entry.getValue()) {
                if(connectionRuntime.connection.getConnectionStatus()==3 && connectionRuntime.connection.getParentConnectionId()>0L){
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public Long getFinishedServiceConnectionCount(){
        long count = 0;
        for (Map.Entry<Integer, List<InetConnectionRuntime>> entry : this.access.connectionManager.inetServEntrySet()) {
            for (InetConnectionRuntime connectionRuntime : entry.getValue()) {
                if(connectionRuntime.connection.getConnectionStatus()==4 && connectionRuntime.connection.getParentConnectionId()>0L){
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public int getAuthenticationAcceptCounter(){
        try {
            return RadiusListener.authenticationAcceptCounter.getCount();
        }catch (Exception e){
            return -1;
        }
    }

    @Override
    public int getAuthenticationRejectCounter(){
        try {
            return RadiusListener.authenticationRejectCounter.getCount();
        }catch (Exception e){
            return -1;
        }
    }

    @Override
    public int getAuthenticationIgnoreCounter(){
        try {
            return RadiusListener.authenticationIgnoreCount.getCount();
        }catch (Exception e){
            return -1;
        }
    }

    @Override
    public int getAntispamIgnoreCounter(){
        try {
            return RadiusListener.antispamIgnoreCount.getCount();
        }catch (Exception e){
            return -1;
        }
    }
}