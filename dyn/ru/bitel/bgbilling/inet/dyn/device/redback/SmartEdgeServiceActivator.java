package ru.bitel.bgbilling.inet.dyn.device.redback;

import java.net.InetAddress;
import java.util.Map;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.event.EventProcessor;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSet;
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
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;

@Deprecated
public class SmartEdgeServiceActivator
	extends ServiceActivatorAdapter
{
	private static final Logger log = Logger.getLogger( SmartEdgeServiceActivator.class );
	
	private RadiusClient radiusClient;
	private RadiusAttributeSet lockAttributes;
	private Map<Integer, RadiusAttributeSet> optionSets;

	@Override
    public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceParams )
        throws Exception
    {
		String nasHost = deviceParams.get( "nas.radius.host", device.getHost() );
		InetAddress nasHostAddr = InetAddress.getByName( nasHost );
		int nasPort = deviceParams.getInt( "nas.radius.port", 1700 );

		byte[] nasSecret = deviceParams.get( "nas.secret", device.getSecret() ).getBytes();

		radiusClient = new RadiusClient( nasHostAddr, nasPort, nasSecret );

		log.info( "Init script for device: " + device.getId() );

		// атрибуты отправляются в CoA при необходимости сброса
		lockAttributes = RadiusAttributeSet.newRadiusAttributeSet( deviceParams.get( "redirect.attributes", "" ) );

		// RADIUS атрибуты по ключу опции
		optionSets = RadiusAttributeSet.newRadiusAttributeSetMap( deviceParams, "option.", "attributes" );

		log.info( "Options map size: " + optionSets.size() );
		
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
		RadiusPacket packet = radiusClient.createModifyRequest();
		
		preparePacket( packet, connection );

		packet.addAttributes( lockAttributes );

		// убрать из DHCP, чтобы выдало NaK
		EventProcessor.getInstance().request( new InetConnectionManager.ConnectionRemoveEvent( connection ) );

		log.info( "Send CoA lock: \n" + packet );

		return radiusClient.sendAsync( packet );
    }
	
	@Override
    public Object connectionModify( ServiceActivatorEvent event )
        throws Exception
    {
		log.info( "Connection modify!" );
		log.info( "oldState: " + event.getOldState() + "; newState: " + event.getNewState() + "; oldOptionSet: " + event.getOldOptions() + "; newOptionSet: " + event.getNewOptions() );

		// если необходимо прекратить доступ - просто закрываем соединение
		if( event.getNewState() == InetServ.STATE_DISABLE )
		{
			return connectionClose( event );
		}

		InetConnection connection = event.getConnection();

		// это Reject-To-Accept коннект, нужно сбросить для инициации нормального коннекта
		if( event.getOldState() == InetServ.STATE_DISABLE && event.getNewState() == InetServ.STATE_ENABLE )
		{
			// убрать из DHCP, чтобы выдало NaK
			EventProcessor.getInstance().request( new InetConnectionManager.ConnectionRemoveEvent( connection ) );
			
			return null;
		}
		else
		{
			RadiusPacket packet = radiusClient.createModifyRequest();
		
			preparePacket( packet, connection );

			for( Integer optionId : event.getNewOptions() )
			{
				RadiusAttributeSet attrs = optionSets.get( optionId );
				if( attrs != null )
				{
					packet.addAttributes( attrs );
				}
			}

			log.info( "Send CoA: \n" + packet );

			return radiusClient.sendAsync( packet );	
		}
    }
	
	private void preparePacket( RadiusPacket packet, InetConnection connection )
	{
		packet.setStringAttribute( -1, RadiusDictionary.Acct_Session_Id, connection.getAcctSessionId() );
	}	
}