package ru.dsi.bgbilling.modules.inet.dyn.device.cisco;

import org.apache.log4j.Logger;
import ru.bitel.bgbilling.kernel.network.dhcp.DhcpProtocolHandler;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusDictionary;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.kernel.network.radius.RadiusProtocolHandler;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.dyn.device.radius.AbstractRadiusProtocolHandler;
import ru.bitel.bgbilling.modules.inet.radius.InetRadiusProcessor;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;
import ru.bitel.common.sql.ConnectionSet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Базовый класс для Cisco ISG
 * Копипаста бителовского, без лишней потехи с option 82
 */
public class ISGProtocolHandler
    extends AbstractRadiusProtocolHandler
    implements RadiusProtocolHandler, DhcpProtocolHandler
{
	private static final Logger logger = Logger.getLogger( ISGProtocolHandler.class );

	/**
	 * Код атрибута - id родительского аккаунтинга
	 */
	protected int parentAcctSessionIdType;

	/**
	 * Префикс id родительского аккаунтинга
	 */
	protected String parentAcctSessionIdPrefix;

	/**
	 * Код атрибута - имя сервиса (для cisco-avpair)
	 */
	protected int serviceNameType;

	/**
	 * Префикс имени сервиса (для cisco-avpair)
	 */
	protected String serviceNamePrefix;

	/**
	 * Имя сервиса, при котором доступ отключен.
	 */
	protected Set<String> disableServiceNames;

	public ISGProtocolHandler()
	{
		super( 9 ); // Cisco
	}

	@Override
	public void init( Setup setup, int moduleId, InetDevice inetDevice, InetDeviceType inetDeviceType, ParameterMap deviceConfig )
	    throws Exception
	{
		super.init( setup, moduleId, inetDevice, inetDeviceType, deviceConfig );

		parentAcctSessionIdType = deviceConfig.getInt( "radius.parentAcctSessionId.type", 1 ); // cisco-avpair
		parentAcctSessionIdPrefix = deviceConfig.get( "radius.parentAcctSessionId.prefix", "parent-session-id=" );
		serviceNameType = deviceConfig.getInt( "radius.serviceName.type", 251 ); // cisco-SSG-Service-Info
		serviceNamePrefix = deviceConfig.get( "radius.serviceName.prefix", "" );

		List<String> disableServiceNames = Utils.toList( deviceConfig.get( "radius.serviceName.disable", "" ) );// INET_FAKE
		if( disableServiceNames.size() > 0 )
		{
			this.disableServiceNames = Collections.newSetFromMap( new LinkedHashMap<String, Boolean>() );
			this.disableServiceNames.addAll( disableServiceNames );
		}
		else
		{
			this.disableServiceNames = null;
		}
	}
	
	@Override
	public void preprocessAccountingRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
	    throws Exception
	{
		int acctStatusType = request.getIntAttribute( -1, RadiusDictionary.Acct_Status_Type, -1 );
        super.preprocessAccountingRequest(request, response, connectionSet);

        // извлекаем parentAcctSessionId
        String parentAcctSessionId;
        // если parentAcctSessionId находится в cisco-avpair - то нужно искать по префиксу
        if( parentAcctSessionIdType == 1 )
        {
            parentAcctSessionId = null;
            // смотрим во всех cisco-avpair атрибутах
            List<RadiusAttribute<?>> attributes = request.getAttributes( radiusVendor, parentAcctSessionIdType );
            if( attributes != null )
            {
                for( RadiusAttribute<?> attr : attributes )
                {
                    @SuppressWarnings("unchecked")
                    String value = ((RadiusAttribute<String>)attr).getValue();
                    if( value.startsWith( parentAcctSessionIdPrefix ) )
                    {
                        parentAcctSessionId = value.substring( parentAcctSessionIdPrefix.length() );
                        break;
                    }
                }
            }
        }
        else
        {
            parentAcctSessionId = request.getStringAttribute( radiusVendor, parentAcctSessionIdType, null );
        }

        // если это аккаунтинг сервисной сессии
        if( parentAcctSessionId != null )
        {
            // извлекаем serviceName
            String serviceName;
            // если serviceName находится в cisco-avpair - то нужно искать по префиксу
            if( serviceNameType == 1 )
            {
                serviceName = null;
                // смотрим во всех cisco-avpair атрибутах
                final List<RadiusAttribute<?>> ras = request.getAttributes( radiusVendor, serviceNameType );
                if( ras != null )
                {
                    for( RadiusAttribute<?> ra : ras )
                    {
                        @SuppressWarnings("unchecked")
                        final String value = ((RadiusAttribute<String>)ra).getValue();
                        if( value.startsWith( serviceNamePrefix ) )
                        {
                            serviceName = value.substring( serviceNamePrefix.length() );
                            break;
                        }
                    }
                }
            }
            else
            {
                serviceName = request.getStringAttribute( radiusVendor, serviceNameType, null );
            }

            if( serviceName == null || !serviceName.startsWith( "N" ) )
            {
                logger.error( "Parent acctSessionId found, but ServiceName is not" );
            }
            else
            {
                serviceName = serviceName.substring( 1 );
            }

            // устанавливаем id родительской сессии
            request.setOption( InetRadiusProcessor.PARENT_ACCT_SESSION_ID, parentAcctSessionId );
            // устанавливаем имя сервиса текущего аккаунтинга
            request.setOption( InetRadiusProcessor.SERVICE_NAME, serviceName );

            // если указан сервис, при котором доступ ограничен - проверяем, не его ли это аккаунтинг,
            // и, если это так, переключаем состояние соединения
            if( disableServiceNames != null && disableServiceNames.contains( serviceName ) )
            {
                // start или update
                if( acctStatusType == 1 || acctStatusType == 3 )
                {
                    logger.debug( "State is disable (from start disable service)" );
                    request.setOption( InetRadiusProcessor.DEVICE_STATE, InetServ.STATE_DISABLE );
                }
                else
                {
                    logger.debug( "State is enable (from stop disable service)" );
                    request.setOption( InetRadiusProcessor.DEVICE_STATE, InetServ.STATE_ENABLE );
                }
            }
        }
	}

}
