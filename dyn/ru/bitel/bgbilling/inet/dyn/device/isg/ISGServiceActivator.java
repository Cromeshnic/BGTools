package ru.bitel.bgbilling.inet.dyn.device.isg;

import java.net.InetAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.event.EventProcessor;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusClient;
import ru.bitel.bgbilling.kernel.network.radius.RadiusDictionary;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.modules.inet.access.InetConnectionManager;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorAdapter;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;

/**
 * use {@link ru.bitel.bgbilling.modules.inet.dyn.device.cisco.ISGServiceActivator}
 */
@Deprecated
public class ISGServiceActivator
	extends ServiceActivatorAdapter	
{
	private static final Logger log = Logger.getLogger( ISGServiceActivator.class );
	
	private RadiusClient radiusClient;
	// ключ - код опции, значение - сервис
	private Map<Integer, String> inetOptionIsgService = new HashMap<Integer, String>();
	// сервис, при запрещении доступа
	private String rejectService;
	
	// отправка в атрибуте cisco-SSG-Command-Code
	private static final int COA_MODE_SSG_COMMAND = 1;
	// отправка в атррибуте cisco-avpair="subscriber:command=deactivate-service"
	private static final int COA_MODE_SUBSCR_COMMAND = 2; 
	
	private int coaMode;
	
	@Override
    public Object init( int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceParams )
        throws Exception
    {
		String nasHost = deviceParams.get( "nas.radius.host", device.getHost() );
		InetAddress nasHostAddr = InetAddress.getByName( nasHost );
		int nasPort = deviceParams.getInt( "nas.radius.port", 1700 );
		coaMode = deviceParams.getInt( "coa.mode", COA_MODE_SSG_COMMAND );
		
		byte[] nasSecret = deviceParams.get( "nas.secret", device.getSecret() ).getBytes();

		radiusClient = new RadiusClient( nasHostAddr, nasPort, nasSecret );

		log.info( "Init script for device: " + device.getId() );

		// вычленение из ISG атрибутов соответствия опций модуля опциям ISG
		String prefix = "=A";

		// определение сервисов на каждой из опций
		for( Map.Entry<String, String> me : deviceParams.sub( "nas.radius.inetOption." ).entrySet() )
		{
			String key = me.getKey();
			String attributes = me.getValue();

			int pos = key.indexOf( "." );
			if( pos <= 0 )
			{
				log.error( "Incorrect attributes, key: " + key );
				continue;
			}

			int inetOptionId = Utils.parseInt( key.substring( 0, pos ) );

			pos = attributes.indexOf( prefix );
			if( pos <= 0 )
			{
				continue;
			}
			String isgService = attributes.substring( pos + prefix.length() );

			inetOptionIsgService.put( inetOptionId, isgService );

			log.info( "Inet option: " + inetOptionId + " => ISG service: " + isgService );
		}

		log.info( "Options map size: " + inetOptionIsgService.size() );

		// сервис, отправляемый в режиме Reject-To-Accept
		rejectService =  deviceParams.get( "nas.radius.realm.reject.attributes", "FAKE" );
		int pos = rejectService.indexOf( prefix );
		if( pos >= 0 )
		{
			rejectService = rejectService.substring( pos + prefix.length() );
		}

		log.info( "Reject service: " + rejectService );

	    return null;
    }
	
	@Override
    public Object destroy()
        throws Exception
    {
	    radiusClient.destroy();
	    return null;
    }

	@Override
    public Object connectionClose( ServiceActivatorEvent event )
        throws Exception
    {
		log.info( "Connection close!" );

		InetConnection connection = event.getConnection();

		// убрать из DHCP, чтобы выдало NaK
		EventProcessor.getInstance().request( new InetConnectionManager.ConnectionRemoveEvent( connection ) );

		Future<Boolean> result = null;
		
		// добавление фейкового сервиса 
		if( Utils.notBlankString( rejectService ) )
		{		
			RadiusPacket packet = radiusClient.createModifyRequest();	
			preparePacket( packet, connection );
			
			if( coaMode == COA_MODE_SSG_COMMAND )
			{
				// добавление cisco-SSG-Command-Code
				packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 252, "\\0xb" + rejectService ) );
			}
			else
			{
				addServiceCommand( rejectService, "activate-service", packet );
			}

			log.info( "Send fake service CoA: \n" + packet );
			result = radiusClient.sendAsync( packet );
		}

		if( coaMode == COA_MODE_SSG_COMMAND )
		{
			// закрытие всех прочих сервисов
			RadiusPacket packet = radiusClient.createModifyRequest();	
			preparePacket( packet, connection );
			
			// закрытие всех прочих сервисов
			addOptionsWithPrefix( packet, inetOptionIsgService.keySet(), "\\0xc" );	
			
			log.info(  "Send close all service CoA: \n" + packet );
			result = radiusClient.sendAsync( packet );
		}
		else
		{
			log.info( "Closing options: " + Utils.toString( event.getOldOptions() ) );
			
			result = sendCommands( connection, event.getOldOptions(), "deactivate-service" );
		}

		// контроллируется выполнение только последнего CoA
		if( result != null )
		{
			return result;
		} 
		else
		{
			return true;
		}
    }

	@Override
    public Object connectionModify( ServiceActivatorEvent event )
        throws Exception
	{
		log.info( "Connection modify!" );

		// если необходимо прекратить доступ - просто закрываем соединение
		if( event.getNewState() == InetServ.STATE_DISABLE )
		{
			return connectionClose( event );
		}

		InetConnection connection = event.getConnection();
		
		Future<Boolean> result = null;
		
		// это Reject-To-Accept коннект, нужно сбросить для инициации нормального коннекта
		if( event.getOldState() == InetServ.STATE_DISABLE && event.getNewState() == InetServ.STATE_ENABLE )
		{
			// убрать из DHCP, чтобы выдало NaK
			EventProcessor.getInstance().request( new InetConnectionManager.ConnectionRemoveEvent( connection ) );
		}
		else
		{
			@SuppressWarnings("unchecked")
	        Collection<Integer> removeOptions = CollectionUtils.subtract( event.getOldOptions(), event.getNewOptions() );
			@SuppressWarnings("unchecked")
	        Collection<Integer> addOptions = CollectionUtils.subtract( event.getNewOptions(), event.getOldOptions() );
	
			// первый CoA с открытием добавившихся опций
			if( addOptions.size() > 0 )
			{
				if( coaMode == COA_MODE_SSG_COMMAND )
				{
					RadiusPacket packet = radiusClient.createModifyRequest();
					preparePacket( packet, connection );
					addOptionsWithPrefix( packet, addOptions, "\\0xb" );
		
					result = radiusClient.sendAsync( packet );
					log.info( "Send adding CoA: \n" + packet );	
				}
				else
				{
					sendCommands( connection, addOptions, "activate-service" );
				}
			}
	
			// второй с закрытием удалённых
			if( removeOptions.size() > 0 )
			{
				if( coaMode == COA_MODE_SSG_COMMAND )
				{
					RadiusPacket packet = radiusClient.createModifyRequest();
					preparePacket( packet, connection );
					addOptionsWithPrefix( packet, removeOptions, "\\0xc" );	
		
					result = radiusClient.sendAsync( packet );
					log.info( "Send closing CoA: \n" + packet );
				}
				else
				{
					sendCommands( connection, addOptions, "deactivate-service" );
				}
			}
		}

		// контроллируется выполнение только последнего CoA
		if( result != null )
		{
			return result;
		} 
		else
		{
			return true;
		}
    }
	
	private void addOptionsWithPrefix( RadiusPacket packet, Collection<Integer> options, String prefix )
	{
		for( Integer option : options )
		{
			String isgService = inetOptionIsgService.get( option );
			if( isgService != null )
			{
				String value = prefix + String.valueOf( isgService );
				// добавление cisco-SSG-Command-Code
				packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 252, value ) );
			}
			else
			{
				log.error( "Not found ISG option for option: " + option );
			}			
		}
	}
	
	public Future<Boolean> sendCommands( InetConnection connection, Collection<Integer> options, String command )
	    throws InvalidKeyException, SocketException, NoSuchAlgorithmException
	{
		Future<Boolean> result = null;
		
	    for( int optionId : options )
	    {
	    	String isgService = inetOptionIsgService.get( optionId );
	    	if( isgService == null )
	    	{
	    		log.error( "Not found ISG option for Inet option: " + optionId );
	    		continue;
	    	}
	    	
	    	RadiusPacket packet = radiusClient.createModifyRequest();	
	    	preparePacket( packet, connection );
	    	
	    	addServiceCommand( isgService, command, packet );
	    	
	    	log.info(  "Send close service CoA: \n" + packet );
	    	result = radiusClient.sendAsync( packet );
	    }
	    return result;
	}

	public void addServiceCommand( String isgService, String command, RadiusPacket packet )
    {
	    packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 1, "subscriber:service-name=" + isgService ) );
	    packet.addAttribute( new RadiusAttribute.RadiusAttributeString( 9, 1, "subscriber:command=" + command ) );
    }	
	
	private void preparePacket( RadiusPacket packet, InetConnection connection )
	{
		packet.setStringAttribute( -1, RadiusDictionary.Acct_Session_Id, connection.getAcctSessionId() );
	}
}