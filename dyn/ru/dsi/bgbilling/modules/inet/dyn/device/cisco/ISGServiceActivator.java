package ru.dsi.bgbilling.modules.inet.dyn.device.cisco;

import org.apache.log4j.Logger;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSet;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.dyn.device.radius.AbstractRadiusServiceActivator;
import ru.bitel.bgbilling.modules.inet.runtime.InetOptionRuntimeMap;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;

import java.util.*;

/**
 * Конфигурация устройства:
 * sa.radius.connection.coa.mode = 1
 *      режим отправки CoA:
 *          0 - отправка в атрибуте cisco-SSG-Command-Code в одном пакете
 *          1 - (default) отправка в атрибуте cisco-SSG-Command-Code по отдельному пакету на сервис
 *          2 - отправка в атрибуте cisco-avpair="subscriber:command=deactivate-service"
 *
 * sa.radius.service.disable =
 *      имена сервисов, при котором доступ отключен
 *      отправляются в режиме Reject-To-Accept
 *      по-умолчанию не указано
 *
 * sa.radius.connection.close.mode = 2
 *      что делать для закрытия соединения:
 *          1 - ничего не делать
 *          2 - (default) посылаем PoD
 *          3 - посылаем subscriber:command=account-logoff
 *
 * sa.radius.connection.close.disableServices = 0
 *      отключать ли сервисы ISG при закрытии
 *          0 - (default) не отключать
 *          1 - отключать (посылаем CoA на отключение всех сервисов перед тем как закрыть соединение по sa.radius.connection.close.mode)
 */
public class ISGServiceActivator
        extends AbstractRadiusServiceActivator
        implements ServiceActivator
{
    private static final Logger logger = Logger.getLogger( ISGServiceActivator.class );

    /**
     * per-realm:
     *      код опции -> набор сервисов ISG
     */
    protected Map<String,Map<Integer, Set<String>>> optionISGServiceMap = new HashMap<String,Map<Integer, Set<String>>>();

    /**
     * имя(имена) сервиса, при котором доступ отключен.
     */
    protected Set<String> disableServiceNames;

    /**
     * Отправка в атрибуте cisco-SSG-Command-Code в одном пакете
     */
    protected static final int COA_MODE_SSG_COMMAND_PACKET = 0;

    /**
     * Отправка в атрибуте cisco-SSG-Command-Code по отдельному пакету на сервис
     */
    protected static final int COA_MODE_SSG_COMMAND = 1;

    /**
     * Отправка в атрибуте cisco-avpair="subscriber:command=deactivate-service"
     */
    protected static final int COA_MODE_SUBSCR_COMMAND = 2;

    /**
     * Режим отправки команд
     */
    protected int coaMode;

    @Deprecated
    protected static final int CLOSE_MODE_POD_DEPRECATED = 0;

    protected static final int CLOSE_MODE_NONE = 1;
    protected static final int CLOSE_MODE_POD = 2;
    protected static final int CLOSE_MODE_SUBSCR_COMMAND = 3;

    protected int closeMode;
    protected boolean disableServicesOnClose;

    public ISGServiceActivator()
    {
        super( null, false, "Acct-Session-Id", false );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceConfig )
            throws Exception
    {
        super.init( setup, moduleId, device, deviceType, deviceConfig );

        this.coaMode = deviceConfig.getInt( "sa.radius.connection.coa.mode", deviceConfig.getInt( "radius.coa.mode", deviceConfig.getInt( "coa.mode", COA_MODE_SSG_COMMAND ) ) );

        //вендор атрибута cisco-SSG-Account-Info (9)
        int ciscoSSGAccountInfo_attribute_vendor=9;
        //id атрибута cisco-SSG-Account-Info (250)
        int ciscoSSGAccountInfo_attribute_id=250;

        Map<Integer, Set<String>> map;
        Set<String> set;
        List<RadiusAttribute<?>> raList;
        InetOptionRuntimeMap inetOptionRuntimeMap = InetOptionRuntimeMap.getInstance(moduleId);
        // определение сервисов на каждой из опций
        for(Map.Entry<String, Map<Integer, RadiusAttributeSet>> e_realm : this.optionRadiusAttributesMap.getRealmMap().entrySet()){

            map = this.optionISGServiceMap.get(e_realm.getKey());
            if(null==map){
                map = new HashMap<Integer, Set<String>>();
                this.optionISGServiceMap.put(e_realm.getKey(), map);
            }
            //Перебираем опции в realm-е
            for(Map.Entry<Integer, RadiusAttributeSet> e_option : e_realm.getValue().entrySet()){
                logger.info("option = "+inetOptionRuntimeMap.get(e_option.getKey()).title+"("+e_option.getKey()+"), realm = "+e_realm.getKey()+", ra = "+e_option.getValue());
                set = null;
                raList = e_option.getValue().getAttributes(ciscoSSGAccountInfo_attribute_vendor, ciscoSSGAccountInfo_attribute_id);
                if(raList!=null){
                    for(RadiusAttribute<?> attr : raList){
                        if(null==set){
                            set = new HashSet<String>();
                        }
                        //вырезаем из атрибута cisco-SSG-Account-Info=ASERVICENAME имя сервиса SERVICENAME
                        set.add(attr.getValue().toString().substring(1));
                    }
                    if(set!=null && set.size()>0){
                        map.put(e_option.getKey(), set);
                    }
                }
            }
        }

        // сервис(ы), отправляемый в режиме Reject-To-Accept
        List<String> disableServiceNames = Utils.toList( deviceConfig.get( "sa.radius.service.disable", deviceConfig.get( "radius.serviceName.disable", "" ) ) );// INET_FAKE
        if( disableServiceNames.size() > 0 )
        {
            this.disableServiceNames = Collections.newSetFromMap( new LinkedHashMap<String, Boolean>() );
            this.disableServiceNames.addAll( disableServiceNames );
        }
        else
        {
            this.disableServiceNames = null;
        }

        logger.info( "Disable services: " + disableServiceNames );

        this.closeMode = deviceConfig.getInt( "sa.radius.connection.close.mode", CLOSE_MODE_POD );
        this.disableServicesOnClose = deviceConfig.getInt( "sa.radius.connection.close.disableServices", 0 ) > 0;

        return null;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Object connectionModify( ServiceActivatorEvent e )//TODO добавить timeout, чтобы не отправлять слишком быстро. Дожидаться ответов например.
            throws Exception
    {
        logger.info( "Connection modify: oldState: " + e.getOldState() + "; newState: " + e.getNewState() + "; oldOptionSet: " + e.getOldOptions() + "; newOptionSet: " + e.getNewOptions() );

        final InetConnection connection = e.getConnection();

        if( e.getNewState() == InetServ.STATE_DISABLE )
        {
            if( !withoutBreak )
            {
                return connectionClose( e );
            }

            // устанавливаем флаг, что нужно будет поменять состояние соединения в базе
            if( needConnectionStateModify )
            {
                e.setConnectionStateModified( true );
            }

            return sendCommands( connection, optionsToServiceNames(e.getRealm(), e.getOldOptions()), disableServiceNames );
        }

        if( e.getOldState() == InetServ.STATE_DISABLE )
        {
            if( !withoutBreak )
            {
                return connectionClose( e );
            }

            // устанавливаем флаг, что нужно будет поменять состояние соединения в базе
            if( needConnectionStateModify )
            {
                e.setConnectionStateModified( true );
            }

            // отключаем disable сервис и включаем активные опции
            return sendCommands( connection, disableServiceNames, optionsToServiceNames(e.getRealm(), e.getNewOptions()) );
        }

        Collection<Integer> removeOptions = e.getOptionsToRemove();
        Collection<Integer> addOptions = e.getOptionsToAdd();

        return sendCommands( connection, optionsToServiceNames(e.getRealm(), removeOptions), optionsToServiceNames(e.getRealm(), addOptions ) );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object connectionClose( ServiceActivatorEvent e )
            throws Exception
    {
        logger.info( "Connection close" );

        Object result;

        final InetConnection connection = e.getConnection();

        if( disableServicesOnClose )
        {
            result = sendCommands( connection, optionsToServiceNames(e.getRealm(), e.getOldOptions()), disableServiceNames );
        }
        else
        {
            result = null;
        }

        switch( closeMode )
        {
            default:
            case CLOSE_MODE_NONE:
            {
                break;
            }

            case CLOSE_MODE_POD_DEPRECATED:
            case CLOSE_MODE_POD:
            {
                RadiusPacket request = radiusClient.createDisconnectRequest();
                prepareRequest( request, connection );

                logger.info( "Send PoD: \n" + request );
                result = radiusClient.sendAsync( request );

                break;
            }

            case CLOSE_MODE_SUBSCR_COMMAND:
            {
                logger.info( "Connection close (logoff)" );

                RadiusPacket packet = radiusClient.createModifyRequest();
                prepareRequest( packet, connection );

                packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 1, "subscriber:command=account-logoff" ) );

                logger.info( "Send logoff CoA:\n" + packet );
                result = radiusClient.sendAsync( packet );

                break;
            }
        }

        return result;
    }

    protected Collection<String> optionsToServiceNames(String realm, final Collection<Integer> options)//, final Collection<String> serviceNames )
    {
        if( options == null || options.size() == 0 )
        {
            return null;
        }

        if(null==realm || "".equals(realm)){
            realm = "default";
        }

        final Set<String> result = Collections.newSetFromMap( new LinkedHashMap<String, Boolean>( options.size() + 2 ) );

        for( Integer option : options )
        {
            Set<String> serviceNames = this.optionISGServiceMap.get(realm).get(option);
            if( serviceNames == null ){
                serviceNames = this.optionISGServiceMap.get("default").get(option);
            }
            if( serviceNames != null )
            {
                result.addAll( serviceNames );
            }
        }

        return result;
    }

    /**
     * Отправка команд на деактивацию и активацию сервисов
     * @param connection - InetConnection
     * @param serviceNamesDeactivate - список сервисов, которые нужно деактивировать
     * @param serviceNamesActivate - список сервисов, которые нужно активировать
     * @return
     * @throws Exception
     */
    protected Object sendCommands( final InetConnection connection, final Collection<String> serviceNamesDeactivate, final Collection<String> serviceNamesActivate )
            throws Exception
    {
        Object result = null;

        if(logger.isInfoEnabled()){
            logger.info("Sending commands to deactivate services (mode="+this.coaMode+"): ["+Utils.toString(serviceNamesDeactivate)+"]");
            logger.info("Sending commands to activate services (mode="+this.coaMode+"): ["+Utils.toString(serviceNamesActivate)+"]");
        }

        switch( coaMode )
        {
            case COA_MODE_SSG_COMMAND_PACKET:
            {
                if( serviceNamesDeactivate != null && serviceNamesDeactivate.size() > 0 )
                {
                    RadiusPacket packet = radiusClient.createModifyRequest();
                    prepareRequest( packet, connection );

                    for( String serviceName : serviceNamesDeactivate )
                    {
                        String value = "\\0xc" + serviceName;
                        // добавление cisco-SSG-Command-Code
                        packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 252, value ) );
                    }

                    result = radiusClient.sendAsync( packet );
                    //logger.info( "Send deactivate services CoA:\n" + packet );
                }

                if( serviceNamesActivate != null && serviceNamesActivate.size() > 0 )
                {
                    RadiusPacket packet = radiusClient.createModifyRequest();
                    prepareRequest( packet, connection );

                    for( String serviceName : serviceNamesActivate )
                    {
                        String value = "\\0xb" + serviceName;
                        // добавление cisco-SSG-Command-Code
                        packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 252, value ) );
                    }

                    result = radiusClient.sendAsync( packet );
                    //logger.info( "Send activate services CoA:\n" + packet );
                }

                break;
            }

            case COA_MODE_SSG_COMMAND:
            {
                if( serviceNamesDeactivate != null && serviceNamesDeactivate.size() > 0 )
                {
                    for( String serviceName : serviceNamesDeactivate )
                    {
                        RadiusPacket packet = radiusClient.createModifyRequest();
                        prepareRequest( packet, connection );

                        String value = "\\0xc" + serviceName;
                        // добавление cisco-SSG-Command-Code
                        packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 252, value ) );

                        //logger.info( "Send deactivate service CoA:\n" + packet );
                        result = radiusClient.sendAsync( packet );
                    }
                }

                if( serviceNamesActivate != null && serviceNamesActivate.size() > 0 )
                {
                    for( String serviceName : serviceNamesActivate )
                    {
                        RadiusPacket packet = radiusClient.createModifyRequest();
                        prepareRequest( packet, connection );

                        String value = "\\0xb" + serviceName;
                        // добавление cisco-SSG-Command-Code
                        packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 252, value ) );

                        //logger.info( "Send activate service CoA:\n" + packet );
                        result = radiusClient.sendAsync( packet );
                    }
                }

                break;
            }

            case COA_MODE_SUBSCR_COMMAND:
            default:
            {
                if( serviceNamesDeactivate != null && serviceNamesDeactivate.size() > 0 )
                {
                    for( String serviceName : serviceNamesDeactivate )
                    {
                        RadiusPacket packet = radiusClient.createModifyRequest();
                        prepareRequest( packet, connection );

                        packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 1, "subscriber:service-name=" + serviceName ) );
                        packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 1, "subscriber:command=deactivate-service" ) );

                        //logger.info( "Send deactivate service CoA:\n" + packet );
                        result = radiusClient.sendAsync( packet );
                    }
                }

                if( serviceNamesActivate != null && serviceNamesActivate.size() > 0 )
                {
                    for( String serviceName : serviceNamesActivate )
                    {
                        RadiusPacket packet = radiusClient.createModifyRequest();
                        prepareRequest( packet, connection );

                        packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 1, "subscriber:service-name=" + serviceName ) );
                        packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 1, "subscriber:command=activate-service" ) );

                        //logger.info( "Send activate service CoA:\n" + packet );
                        result = radiusClient.sendAsync( packet );
                    }
                }

                break;
            }
        }

        return result;
    }
}