package ru.dsi.bgbilling.modules.inet.mxbean;

import org.apache.log4j.Logger;
import ru.bitel.bgbilling.kernel.network.radius.RadiusListener;
import ru.bitel.bgbilling.modules.inet.accounting.Accounting;
import ru.bitel.bgbilling.modules.inet.accounting.InetConnectionCallRuntime;

import javax.management.*;
import java.beans.ConstructorProperties;
import java.lang.management.ManagementFactory;
import java.util.Map;

/**
 * @see RadiusStatusAccountingMXBean
 */
public class RadiusStatusAccounting implements RadiusStatusAccountingMXBean {

    private Accounting accounting;
    private static final Logger logger = Logger.getLogger(RadiusStatusAccounting.class);

    @ConstructorProperties({"accounting"})
    public RadiusStatusAccounting(Accounting accounting){
        this.accounting = accounting;

        //Регистрируем mxbean
        try {
            MBeanServer mbs =
                    ManagementFactory.getPlatformMBeanServer();
            ObjectName mxbeanName = new ObjectName("ru.dsi.bgbilling.modules.inet.mxbean:type=RadiusStatusAccounting");
            mbs.registerMBean(this, mxbeanName);
        } catch (InstanceAlreadyExistsException e) {
            logger.error("error on registering MXBean RadiusStatusAccounting", e);
        } catch (MBeanRegistrationException e) {
            logger.error("error on registering MXBean RadiusStatusAccounting", e);
        } catch (NotCompliantMBeanException e) {
            logger.error("error on registering MXBean RadiusStatusAccounting", e);
        } catch (MalformedObjectNameException e) {
            logger.error("error on registering MXBean RadiusStatusAccounting", e);
        }
    }

    @Override
    public Long getActiveParentConnectionCount(){
        long count = 0;
        for (Map.Entry<Long, InetConnectionCallRuntime> entry : this.accounting.connectionMapCall.getSessionMap().entrySet()) {
            if(entry.getValue().connection.getConnectionStatus()==1 && !entry.getValue().isServiceSession()){
                count++;
            }
        }
        return count;
    }

    @Override
    public Long getSuspendedParentConnectionCount(){
        long count = 0;
        for (Map.Entry<Long, InetConnectionCallRuntime> entry : this.accounting.connectionMapCall.getSessionMap().entrySet()) {
            if(entry.getValue().connection.getConnectionStatus()==2 && !entry.getValue().isServiceSession()){
                count++;
            }
        }
        return count;
    }

    @Override
    public Long getClosedParentConnectionCount(){
        long count = 0;
        for (Map.Entry<Long, InetConnectionCallRuntime> entry : this.accounting.connectionMapCall.getSessionMap().entrySet()) {
            if(entry.getValue().connection.getConnectionStatus()==3 && !entry.getValue().isServiceSession()){
                count++;
            }
        }
        return count;
    }

    @Override
    public Long getFinishedParentConnectionCount(){
        long count = 0;
        for (Map.Entry<Long, InetConnectionCallRuntime> entry : this.accounting.connectionMapCall.getSessionMap().entrySet()) {
            if(entry.getValue().connection.getConnectionStatus()==4 && !entry.getValue().isServiceSession()){
                count++;
            }
        }
        return count;
    }

    @Override
    public Long getActiveServiceConnectionCount(){
        long count = 0;
        for (Map.Entry<Long, InetConnectionCallRuntime> entry : this.accounting.connectionMapCall.getSessionMap().entrySet()) {
            if(entry.getValue().connection.getConnectionStatus()==1 && entry.getValue().isServiceSession()){
                count++;
            }
        }
        return count;
    }

    @Override
    public Long getSuspendedServiceConnectionCount(){
        long count = 0;
        for (Map.Entry<Long, InetConnectionCallRuntime> entry : this.accounting.connectionMapCall.getSessionMap().entrySet()) {
            if(entry.getValue().connection.getConnectionStatus()==2 && entry.getValue().isServiceSession()){
                count++;
            }
        }
        return count;
    }

    @Override
    public Long getClosedServiceConnectionCount(){
        long count = 0;
        for (Map.Entry<Long, InetConnectionCallRuntime> entry : this.accounting.connectionMapCall.getSessionMap().entrySet()) {
            if(entry.getValue().connection.getConnectionStatus()==3 && entry.getValue().isServiceSession()){
                count++;
            }
        }
        return count;
    }

    @Override
    public Long getFinishedServiceConnectionCount(){
        long count = 0;
        for (Map.Entry<Long, InetConnectionCallRuntime> entry : this.accounting.connectionMapCall.getSessionMap().entrySet()) {
            if(entry.getValue().connection.getConnectionStatus()==4 && entry.getValue().isServiceSession()){
                count++;
            }
        }
        return count;
    }

    @Override
    public int getAccountingStartCounter(){
        try {
            return RadiusListener.accountingStartCounter.getCount();
        }catch (Exception e){
            return -1;
        }
    }

    @Override
    public int getAccountingStopCounter(){
        try {
            return RadiusListener.accountingStopCounter.getCount();
        }catch (Exception e){
            return -1;
        }
    }

    @Override
    public int getAccountingUpdateCounter(){
        try {
            return RadiusListener.accountingUpdateCounter.getCount();
        }catch (Exception e){
            return -1;
        }
    }

    @Override
    public int getAccountingUpdateIgnoreCounter(){
        try {
            return RadiusListener.accountingUpdateIgnoreCount.getCount();
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