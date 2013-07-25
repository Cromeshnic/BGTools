package ru.bitel.bgbilling.modules.inet.dyn.device.redback;

import java.nio.ByteBuffer;
import java.util.List;

import ru.bitel.bgbilling.kernel.network.dhcp.DhcpPacket;
import ru.bitel.bgbilling.kernel.network.dhcp.DhcpProtocolHandler;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusDictionary;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket.RadiusPacketOption;
import ru.bitel.bgbilling.kernel.network.radius.RadiusProtocolHandler;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.server.InetUtils;
import ru.bitel.bgbilling.modules.inet.radius.InetRadiusProcessor;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;
import ru.bitel.common.sql.ConnectionSet;

public class SmartEdgeClipsProtocolHandler
    extends SmartEdgeProtocolHandler
    implements RadiusProtocolHandler, DhcpProtocolHandler
{
	public static final int Agent_Remote_Id = 96;
	public static final int Agent_Circuit_Id = 97;
	public static final int Mac_Addr = 145;
	public static final int DHCP_Option = 202;

	/**
	 * Тип поиска сервиса
	 */
	protected int[][] servSearchModes;
	
	/**
	 * Код атрибута - agent-remote-id
	 */
	protected int agentOptionRemoteIdType;
	protected int agentOptionRemoteIdPosition = -1;
	protected int agentOptionRemoteIdLength = -1;
	
	/**
	 * Код атрибута - agent-circuit-id
	 */
	protected int agentOptionCircuitIdType;
	
	/**
	 * Нужно ли удалять заголовок (2 байта, тип+длина) из значения DHCP-опции
	 */
	protected int agentOptionRemoveHeader;
	
	@Override
	public void init( Setup setup, int moduleId, InetDevice inetDevice, InetDeviceType inetDeviceType, ParameterMap deviceConfig )
		throws Exception
	{
		super.init( setup, moduleId, inetDevice, inetDeviceType, deviceConfig );
		
		servSearchModes = InetUtils.parseSearchModes( deviceConfig.get( "radius.servSearchMode",
		                                                                     deviceConfig.get( "radius.serviceSearchMode", String.valueOf( InetDevice.SERV_SEARCH_MODE_LOGIN ) ) ) );

		agentOptionRemoteIdType = deviceConfig.getInt( "radius.agent.option.remoteId.type", deviceConfig.getInt( "radius.agentRemoteId.type", Agent_Remote_Id ) );
		agentOptionRemoteIdPosition = deviceConfig.getInt( "radius.agent.option.remoteId.position", 2 );
		agentOptionRemoteIdLength = deviceConfig.getInt( "radius.agent.option.remoteId.length", -1 );
		
		agentOptionCircuitIdType = deviceConfig.getInt( "radius.agent.option.circuitId.type", Agent_Circuit_Id );
		agentOptionRemoveHeader = deviceConfig.getInt( "radius.agent.option.removeHeader", 0 );
	}

	/**
	 * Установка username
	 * @param request
	 */
	protected void setUsername( final RadiusPacket request )
	{
		String macAddr = request.getStringAttribute( radiusVendor, Mac_Addr, null );
		if( macAddr != null )
		{
			String callingStation = macAddr.replaceAll( "\\-", "" );

			request.setStringAttribute( -1, RadiusDictionary.Calling_Station_Id, callingStation );
		}

		byte[] remoteId = request.getByteAttribute( radiusVendor, Agent_Remote_Id, null );
		byte[] circuitId = request.getByteAttribute( radiusVendor, Agent_Circuit_Id, null );

		if( remoteId != null && circuitId != null )
		{
			String userName = Utils.bytesToString( remoteId, true, null ) + ":" + Utils.bytesToString( circuitId, true, null );
			userName = userName.toLowerCase();

			request.setStringAttribute( -1, 1, userName );
		}
	}
	
	/**
	 * Установка опции option c удалением заголовка, если необходимо (обычно два байта - тип и длина DHCP-субопции)
	 * @param request
	 * @param ra
	 * @param remove
	 * @param option
	 */
	protected void setAgentOption( final RadiusPacket request, final RadiusAttribute<?> ra, int position, int length, final RadiusPacketOption<Object> option )
	{
		if( position > 0 || length >= 0 )
		{
			ByteBuffer data = ra.getData();
			data.position( position );
			
			if( length >= 0 )
			{
				data.limit( position + length );
			}
			
			data = data.slice();

			request.setOption( option, data );
		}
		else
		{
			request.setOption( option, ra.getValue() );
		}
	}

	protected void setAgentOption( final RadiusPacket request, final List<RadiusAttribute<?>> ras, int optionIdPosition, int optionIdValue, int valuePosition, int valueLength,
									final RadiusPacketOption<Object> option )
	{
		for( int i = 0, size = ras.size(); i < size; i++ )
		{
			RadiusAttribute<?> ra = ras.get( i );
			ByteBuffer data = ra.getData();
			if( data.get( optionIdPosition ) == optionIdValue )
			{
				data.position( valuePosition );

				if( valueLength >= 0 )
				{
					data.limit( valuePosition + valueLength );
				}
				
				data = data.slice();
				request.setOption( option, data );
			}
		}
	}

	/**
	 * Установка опций в запрос устройства-агента для последующей обработки
	 * @param request
	 */
	protected void setAgentOptions( final RadiusPacket request )
	{
		// нужно установить правильный agentRemoteId, чтобы было найдено агентское устройство
		switch( agentOptionRemoteIdType )
		{
			case 0:
				break;

			case DHCP_Option:
			{
				List<RadiusAttribute<?>> ras = request.getAttributes( radiusVendor, DHCP_Option );
				if( ras != null )
				{
					setAgentOption( request, ras, 3, 1, 2 + agentOptionRemoteIdPosition, agentOptionRemoteIdLength, InetRadiusProcessor.AGENT_REMOTE_ID );
				}

				break;
			}

			default:
			{
				RadiusAttribute<?> ra = request.getAttribute( radiusVendor, agentOptionRemoteIdType );
				if( ra != null )
				{
					setAgentOption( request, ra, agentOptionRemoveHeader + agentOptionRemoteIdPosition, agentOptionRemoteIdLength, InetRadiusProcessor.AGENT_REMOTE_ID );
				}

				break;
			}
		}

		switch( agentOptionCircuitIdType )
		{
			case 0:
				break;

			case DHCP_Option:
			{
				List<RadiusAttribute<?>> ras = request.getAttributes( radiusVendor, DHCP_Option );
				if( ras != null )
				{
					setAgentOption( request, ras, 3, 2, 2, -1, InetRadiusProcessor.AGENT_CIRCUIT_ID );
				}

				break;
			}

			default:
			{
				RadiusAttribute<?> ra = request.getAttribute( radiusVendor, agentOptionCircuitIdType );
				if( ra != null )
				{
					setAgentOption( request, ra, agentOptionRemoveHeader, -1, InetRadiusProcessor.AGENT_CIRCUIT_ID );
				}

				break;
			}
		}
	}

	@Override
	public void preprocessAccessRequest( final RadiusPacket request, final RadiusPacket response, final ConnectionSet connectionSet )
		throws Exception
	{
		super.preprocessAccessRequest( request, response, connectionSet );
		// устанавливаем поле username
		setUsername( request );
		// устанавливаем agent-remote-id
		setAgentOptions( request );
	}

	@Override
	public void postprocessAccessRequest( final RadiusPacket request, final RadiusPacket response, final ConnectionSet connectionSet )
	    throws Exception
	{
		super.postprocessAccessRequest( request, response, connectionSet );

		response.removeAttributes( -1, RadiusDictionary.Framed_IP_Address );
	}

	@Override
	protected void preprocessAccountingRequestImpl( final int acctStatusType, final RadiusPacket request, final RadiusPacket response,
													final ConnectionSet connectionSet )
		throws Exception
	{
		super.preprocessAccountingRequestImpl( acctStatusType, request, response, connectionSet );

		switch( acctStatusType )
		{
			// если сервисный аккаунтинг
			case 101:
			case 102:
			case 103:
			{
			}
				break;

			default:
			{
				// устанавливаем поле username
				setUsername( request );
				// устанавливаем agent-remote-id
				setAgentOptions( request );
			}
				break;
		}
	}

	@Override
	public void preprocessDhcpRequest( DhcpPacket request, DhcpPacket response )
	    throws Exception
	{
		// необходимо для старого поиска по логину
		if( servSearchModes[0][0] == InetDevice.SERV_SEARCH_MODE_LOGIN )
		{
			try
			{
				byte[] circuitId = request.getSubOption( (byte)1 ).value;
				byte[] remoteId = request.getSubOption( (byte)2 ).value;
				byte[] mac = new byte[6];
				byte[] port = new byte[1];
				System.arraycopy( circuitId, 5, port, 0, 1 );
				System.arraycopy( remoteId, 2, mac, 0, 6 );
				request.setSubOption( (byte)1, port );
				request.setSubOption( (byte)2, mac );
			}
			catch( java.lang.NullPointerException e )
			{
				return;
			}
		}
	}

	@Override
	public void postprocessDhcpRequest( DhcpPacket request, DhcpPacket response )
	    throws Exception
	{
	}
}
