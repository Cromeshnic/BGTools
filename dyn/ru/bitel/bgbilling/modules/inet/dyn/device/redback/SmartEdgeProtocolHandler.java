package ru.bitel.bgbilling.modules.inet.dyn.device.redback;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.network.dhcp.DhcpProtocolHandler;
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

public class SmartEdgeProtocolHandler
    extends AbstractRadiusProtocolHandler
    implements RadiusProtocolHandler, DhcpProtocolHandler
{
	private static final Logger logger = Logger.getLogger( SmartEdgeProtocolHandler.class );

	/**
	 * Код атрибута - id родительского аккаунтинга
	 */
	protected int parentAcctSessionIdType;

	/**
	 * Код атрибута - имя сервиса
	 */
	protected int serviceNameType;

	/**
	 * Имя сервиса, при котором доступ отключен.
	 */
	protected String disableServiceName;

	public SmartEdgeProtocolHandler()
	{
		super( 2352 ); // Redback
	}

	@Override
	public void init( Setup setup, int moduleId, InetDevice inetDevice, InetDeviceType inetDeviceType, ParameterMap deviceConfig )
	    throws Exception
	{
		super.init( setup, moduleId, inetDevice, inetDeviceType, deviceConfig );
		
		parentAcctSessionIdType = deviceConfig.getInt( "radius.parentAcctSessionId.type", 50 );
		serviceNameType = deviceConfig.getInt( "radius.serviceName.type", 190 );

		disableServiceName = deviceConfig.get( "radius.serviceName.disable", null ); // NOAUTH
	}

	@Override
	public void preprocessAccessRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
	    throws Exception
	{
		super.preprocessAccessRequest( request, response, connectionSet );
	}

	@Override
	public void postprocessAccessRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
	    throws Exception
	{
	}

	@Override
	public void preprocessAccountingRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
	    throws Exception
	{
		int acctStatusType = request.getIntAttribute( -1, RadiusDictionary.Acct_Status_Type, -1 );
		this.preprocessAccountingRequestImpl( acctStatusType, request, response, connectionSet );
	}

	protected void preprocessAccountingRequestImpl( int acctStatusType, RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
	    throws Exception
	{
		// устанавливаем MAC-адрес
		setMacAddress( request );

		switch( acctStatusType )
		{
			// если сервисный аккаунтинг
			case 101:
			case 102:
			case 103:
			{
				// получаем id родительского соединения
				final String parentAcctSessionId = request.getStringAttribute( -1, parentAcctSessionIdType, null );
				// получаем имя сервиса, по которому идет аккаунтинг
				final String serviceName = request.getStringAttribute( radiusVendor, serviceNameType, null );

				logger.debug( "parentAcctSessionId=" + parentAcctSessionId + ", serviceName=" + serviceName );

				// подменяем Acct-Status-Type, чтобы биллинг понял типы пакетов
				request.setIntAttribute( -1, RadiusDictionary.Acct_Status_Type, acctStatusType - 100 );
				// устанавливаем id родительской сессии
				request.setOption( InetRadiusProcessor.PARENT_ACCT_SESSION_ID, parentAcctSessionId );
				// устанавливаем имя сервиса текущего аккаунтинга
				request.setOption( InetRadiusProcessor.SERVICE_NAME, serviceName );

				// если указан сервис, при котором доступ ограничен - проверяем, не его ли это аккаунтинг,
				// и, если это так, переключаем состояние соединения
				if( Utils.notBlankString( disableServiceName ) && disableServiceName.equals( serviceName ) )
				{
					// start или update
					if( acctStatusType == 101 || acctStatusType == 103 )
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

				/*Integer ipaddr = request.getIntAttribute( radiusVendor, 132, null );
				if( ipaddr != null )
				{
					request.setIntAttribute( -1, RadiusDictionary.Framed_IP_Address, ipaddr );
				}*/
			}
				break;

			default:
			{
				// для родительского аккаунтинга устанавливаем состояние по наличию определенных атрибутов
				setStateFromAttributes( request );
			}
				break;
		}
	}
}
