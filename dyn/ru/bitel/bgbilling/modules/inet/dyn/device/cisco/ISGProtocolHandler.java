package ru.bitel.bgbilling.modules.inet.dyn.device.cisco;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.network.dhcp.DhcpOption;
import ru.bitel.bgbilling.kernel.network.dhcp.DhcpPacket;
import ru.bitel.bgbilling.kernel.network.dhcp.DhcpProtocolHandler;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusDictionary;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.kernel.network.radius.RadiusProtocolHandler;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket.RadiusPacketOption;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.api.server.InetUtils;
import ru.bitel.bgbilling.modules.inet.dyn.device.radius.AbstractRadiusProtocolHandler;
import ru.bitel.bgbilling.modules.inet.radius.InetRadiusProcessor;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;
import ru.bitel.common.sql.ConnectionSet;

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
	
	private int portLength;
	
	/**
	 * Тип поиска сервиса
	 */
	protected int[][] servSearchModes;
	
	/**
	 * Код атрибута - agent-remote-id
	 */
	protected int agentOptionRemoteIdType;
	
	/**
	 * Префикс атрибута - agent-circuit-id
	 */
	protected String agentOptionRemoteIdPrefix;
	
	/**
	 * Код атрибута - agent-circuit-id
	 */
	protected int agentOptionCircuitIdType;
	
	/**
	 * Префикс атрибута - agent-circuit-id
	 */
	protected String agentOptionCircuitIdPrefix;
	
	/**
	 * Нужно ли удалять заголовок (2 байта, тип+длина) из значения DHCP-опции
	 */
	protected int agentOptionRemoveHeader;

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

		this.servSearchModes = InetUtils.parseSearchModes( deviceConfig.get( "radius.servSearchMode",
		                                                                     deviceConfig.get( "radius.serviceSearchMode", String.valueOf( InetDevice.SERV_SEARCH_MODE_LOGIN ) ) ) );

		portLength = ISGUtils.getPortLength( deviceConfig );

		logger.info( "ISG port length: " + portLength );
		
		agentOptionRemoteIdType = deviceConfig.getInt( "radius.agent.option.remoteId.type", deviceConfig.getInt( "radius.agentRemoteId.type", 1 ) );
		agentOptionRemoteIdPrefix = deviceConfig.get( "radius.agent.option.remoteId.prefix", "remote-id-tag=" );
		agentOptionCircuitIdType = deviceConfig.getInt( "radius.agent.option.circuitId.type", 1 );
		agentOptionCircuitIdPrefix = deviceConfig.get( "radius.agent.option.circuitId.prefix", "circuit-id-tag=" );
		agentOptionRemoveHeader = deviceConfig.getInt( "radius.agent.option.removeHeader", 2 );
	}
	
	/**
	 * Установка опции option c удалением заголовка, если необходимо (обычно два байта - тип и длина DHCP-субопции)
	 * @param request
	 * @param ra
	 * @param remove
	 * @param option
	 */
	protected void setAgentOption( final RadiusPacket request, final Object value, final RadiusPacketOption<Object> option )
	{
		if( value instanceof String )
		{
			String valueString = (String)value;
			valueString = valueString.substring( agentOptionRemoveHeader * 2 );
			request.setOption( option, valueString );
		}
		else if( value instanceof byte[] )
		{
			byte[] valueBytes = (byte[])value;
			valueBytes = Arrays.copyOfRange( valueBytes, agentOptionRemoveHeader, valueBytes.length );
			request.setOption( option, valueBytes );
		}
		else
		{
			logger.error( "Unknown value type for option " + option );
		}
	}

	/**
	 * Установка опций в запрос устройства-агента для последующей обработки
	 * @param request
	 */
	protected void setAgentOptions( final RadiusPacket request )
	{
		final Object agentRemoteId = getAttributeValue( request, radiusVendor, agentOptionRemoteIdType, agentOptionRemoteIdPrefix );
		if( agentRemoteId != null )
		{
			setAgentOption( request, agentRemoteId, InetRadiusProcessor.AGENT_REMOTE_ID );
		}

		final Object agentCircuitId = getAttributeValue( request, radiusVendor, agentOptionCircuitIdType, agentOptionCircuitIdPrefix );
		if( agentCircuitId != null )
		{
			setAgentOption( request, agentCircuitId, InetRadiusProcessor.AGENT_CIRCUIT_ID );
		}
	}

	/**
	 * Установка username
	 * @param request
	 */
	protected void setUsername( final RadiusPacket request )
	{
		// перенос последней части UserName в атрибут Calling-Station-Id (MAC адрес)
		String userName = request.getStringAttribute( -1, 1, null );
		if( userName != null )
		{
			// перенос последней части UserName в атрибут Calling-Station-Id (MAC адрес)
			int pos = userName.lastIndexOf( ':' );
			if( pos > 0 )
			{
				request.setStringAttribute( -1, 31, userName.substring( pos + 1 ) );
				request.setStringAttribute( -1, 1, userName = userName.substring( 0, pos ) );
			}

			if( portLength < ISGUtils.MAX_PORT_LENGTH )
			{
				// урезание circuitId (порта) до последних x символов
				pos = userName.lastIndexOf( ':' );
				if( pos > 0 )
				{
					request.setStringAttribute( -1, RadiusDictionary.User_Name, userName.substring( 0, pos + 1 ) + userName.substring( userName.length() - portLength * 2 ) );
				}
			}
		}
	}

	@Override
	public void preprocessAccessRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
	    throws Exception
	{
		super.preprocessAccessRequest( request, response, connectionSet );
		// устанавливаем поле username
		setUsername( request );
		// устанавливаем agent-remote-id
		setAgentOptions( request );
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
		// устанавливаем поле username
		setUsername( request );
		// устанавливаем agent-remote-id
		setAgentOptions( request );

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

		// если аккаунтинг по родительской сессии
		if( parentAcctSessionId == null )
		{
			// для родительского аккаунтинга устанавливаем состояние по наличию определенных атрибутов
			setStateFromAttributes( request );
		}
		// если это аккаунтинг сервисной сессии
		else
		{
			request.setOption( InetRadiusProcessor.DEVICE_STATE, null );
			
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
	
	@Override
	public void preprocessDhcpRequest( DhcpPacket request, DhcpPacket response )
	    throws Exception
	{
		// необходимо для старого поиска по логину
		if( servSearchModes[0][0] == InetDevice.SERV_SEARCH_MODE_LOGIN )
		{
			DhcpOption option = request.getSubOption( (byte)1 );
			if( option != null )
			{
				byte[] currentValue = option.value;
				// circuitId вида 0004000e000a
				if( currentValue.length == 6 )
				{
					byte[] value = new byte[portLength];
					// оставляем только порт, при этом длина зарезается но для разобранного пакета
					// это уже не критично
					System.arraycopy( currentValue, 6 - portLength, value, 0, portLength );
					request.setSubOption( (byte)1, value );
				}
			}
		}
	}
}
