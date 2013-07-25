package ru.bitel.bgbilling.modules.inet.dyn.device.cisco;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.event.EventProcessor;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.modules.inet.access.InetConnectionManager;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.dyn.device.radius.AbstractRadiusServiceActivator;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;

public class ISGServiceActivator
    extends AbstractRadiusServiceActivator
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( ISGServiceActivator.class );

	/**
	 * Ключ - код опции, значение - сервис
	 */
	protected Map<Integer, String> inetOptionIsgService = new HashMap<Integer, String>();

	/**
	 * Имя(имена) сервиса, при котором доступ отключен.
	 */
	protected Set<String> disableServiceNames;

	/**
	 * Отправка в атрибуте cisco-SSG-Command-Code
	 */
	protected static final int COA_MODE_SSG_COMMAND_PACKET = 0;

	/**
	 * Отправка в атрибуте cisco-SSG-Command-Code
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

	/**
	 * Ничего не делать для закрытия соединения.
	 * @see #disableServicesOnClose
	 */
	protected static final int CLOSE_MODE_NONE = 1;

	/**
	 * Посылать PoD пакет для закрытия соединения.
	 * @see #disableServicesOnClose
	 */
	protected static final int CLOSE_MODE_POD = 2;

	/**
	 * Посылать subscriber:command=account-logoff для закрытия соединения.
	 * @see #disableServicesOnClose
	 */
	protected static final int CLOSE_MODE_SUBSCR_COMMAND = 3;

	/**
	 * Режим закрытия соединения.
	 * @see #CLOSE_MODE_NONE
	 * @see #CLOSE_MODE_POD
	 * @see #CLOSE_MODE_SUBSCR_COMMAND
	 */
	protected int closeMode;

	/**
	 * Режим закрытия соединения для переключения из отключен во включен (если {@link #withoutBreak}=false).
	 * @see #CLOSE_MODE_NONE
	 * @see #CLOSE_MODE_POD
	 * @see #CLOSE_MODE_SUBSCR_COMMAND
	 */
	protected int closeEnableMode;

	/**
	 * Нужно ли посылать CoA при переводе из disable в enable (при withoutBreak=false)
	 */
	protected boolean coaOnEnable;

	/**
	 * Нужно ли закрывать сервисы при закрытии сессии.
	 */
	protected boolean disableServicesOnClose;

	/**
	 * Нужно ли удалять из keymap вторичной авторизации. keymap используется в InetDhcpHelperProcessor и InetRadiusHelperProcessor.
	 * При удалении из keymap InetDhcpHelperProcessor начнет выдавать NAK, InetRadiusHelperProcessor перестанет авторизовать.
	 * Для работы с ISG+DHCP должен быть true.
	 */
	protected boolean closeRemoveFromKeyMap;

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

		// вычленение из ISG атрибутов соответствия опций модуля опциям ISG
		String prefix = "cisco-SSG-Account-Info=A";

		// определение сервисов на каждой из опций
		for( Map.Entry<Integer, ParameterMap> e : deviceConfig.subIndexed( "radius.inetOption." ).entrySet() )
		{
			int option = e.getKey();
			String attributes = e.getValue().get( "attributes", "" );

			if( attributes.startsWith( prefix ) )
			{
				String isgService = attributes.substring( prefix.length() );
				inetOptionIsgService.put( option, isgService );

				logger.info( "Inet option: " + option + " => ISG service: " + isgService );
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

		this.coaOnEnable = deviceConfig.getInt( "sa.radius.connection.coa.onEnable", 0 ) > 0;

		this.closeMode = deviceConfig.getInt( "sa.radius.connection.close.mode", CLOSE_MODE_NONE );
		this.closeEnableMode = deviceConfig.getInt( "sa.radius.connection.close.enableMode", CLOSE_MODE_NONE );

		// при отключечнии соединения по умолчанию убирать из keymap (т.е. начинать посылать NAK на DHCP запросы)
		this.closeRemoveFromKeyMap = deviceConfig.getInt( "sa.radius.connection.close.removeFromKeyMap", 1 ) > 0;

		// при отключении соединения по умолчанию отключать сервисы
		this.disableServicesOnClose = deviceConfig.getInt( "sa.radius.connection.close.disableServices", 1 ) > 0;

		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object connectionModify( ServiceActivatorEvent e )
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

			return sendCommands( connection, optionsToServiceNames( e.getOldOptions(), null ), disableServiceNames );
		}

		if( e.getOldState() == InetServ.STATE_DISABLE )
		{
			if( !withoutBreak )
			{
				if( closeRemoveFromKeyMap )
				{
					// убрать из DHCP, чтобы выдало NaK
					logger.debug( "Remove connection from key map." );
					EventProcessor.getInstance().request( new InetConnectionManager.ConnectionRemoveEvent( connection ) );
				}

				Object result = connectionClose( connection, closeEnableMode, null );

				if( !coaOnEnable )
				{
					return result;
				}
			}

			// устанавливаем флаг, что нужно будет поменять состояние соединения в базе
			if( needConnectionStateModify )
			{
				e.setConnectionStateModified( true );
			}

			// отключаем disable сервис и включаем активные опции
			return sendCommands( connection, disableServiceNames, optionsToServiceNames( e.getNewOptions(), null ) );
		}

		Collection<Integer> removeOptions = e.getOptionsToRemove();
		Collection<Integer> addOptions = e.getOptionsToAdd();

		return sendCommands( connection, optionsToServiceNames( removeOptions, null ), optionsToServiceNames( addOptions, null ) );
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

		if( closeRemoveFromKeyMap )
		{
			// убрать из DHCP, чтобы выдало NaK
			logger.debug( "Remove connection from key map." );
			EventProcessor.getInstance().request( new InetConnectionManager.ConnectionRemoveEvent( connection ) );
		}

		if( disableServicesOnClose )
		{
			result = sendCommands( connection, optionsToServiceNames( e.getOldOptions(), null ), disableServiceNames );
		}
		else
		{
			result = null;
		}

		return connectionClose( connection, closeMode, result );
	}

	/**
	 * Закрытие соединения по указанному режиму.
	 * @see #CLOSE_MODE_NONE
	 * @see #CLOSE_MODE_POD
	 * @see #CLOSE_MODE_SUBSCR_COMMAND
	 * @param connection
	 * @param closeMode
	 * @param result
	 * @return
	 * @throws Exception
	 */
	protected Object connectionClose( final InetConnection connection, final int closeMode, Object result )
	    throws Exception
	{
		logger.info( "Connection close mode " + closeMode );

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

	/**
	 * Преобразование кодов опций в названия сервисов ISG.
	 * @param options
	 * @param serviceNames
	 * @return
	 */
	protected Collection<String> optionsToServiceNames( final Collection<Integer> options, final Collection<String> serviceNames )
	{
		if( options == null || options.size() == 0 )
		{
			return serviceNames;
		}

		final Set<String> result = Collections.newSetFromMap( new LinkedHashMap<String, Boolean>( options.size() + 2 ) );
		if( serviceNames != null )
		{
			result.addAll( serviceNames );
		}

		for( Integer option : options )
		{
			String serviceName = inetOptionIsgService.get( option );
			if( serviceName == null )
			{
				logger.info( "Not found ISG service for Inet option: " + option );
				continue;
			}

			result.add( serviceName );
		}

		return result;
	}

	/**
	 * Отправка команд на деактивацию и активацию сервисов
	 * @param connection
	 * @param serviceNamesDeactivate - список сервисов, которые нужно деактивировать
	 * @param serviceNamesActivate - список сервисов, которые нужно активировать
	 * @return
	 * @throws Exception
	 */
	protected Object sendCommands( final InetConnection connection, final Collection<String> serviceNamesDeactivate, final Collection<String> serviceNamesActivate )
	    throws Exception
	{
		Object result = null;

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
					logger.info( "Send deactivate services CoA:\n" + packet );
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
					logger.info( "Send activate services CoA:\n" + packet );
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

						logger.info( "Send deactivate service CoA:\n" + packet );
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

						logger.info( "Send activate service CoA:\n" + packet );
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

						logger.info( "Send deactivate service CoA:\n" + packet );
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

						logger.info( "Send activate service CoA:\n" + packet );
						result = radiusClient.sendAsync( packet );
					}
				}

				break;
			}
		}

		return result;
	}
}
