package ru.bitel.bgbilling.modules.inet.dyn.device.redback;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSet;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.dyn.device.radius.AbstractRadiusServiceActivator;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;

public class SmartEdgeServiceActivator
    extends AbstractRadiusServiceActivator
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( SmartEdgeServiceActivator.class );

	protected RadiusAttributeSet serviceCloseAttributes;

	public SmartEdgeServiceActivator()
	{
		this( false );
	}

	protected SmartEdgeServiceActivator( boolean defaultWithoutBreak )
	{
		super( "sa.radius.option.", defaultWithoutBreak, "Acct-Session-Id", false );
	}

	@Override
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceConfig )
	        throws Exception
	{
		super.init( setup, moduleId, device, deviceType, deviceConfig );

		serviceCloseAttributes = RadiusAttributeSet.newRadiusAttributeSet( deviceConfig.get( "sa.radius.service.closeAttributes", deviceConfig.get( "close.attributes", "Deactivate-Service-Name:1=RSE-SVC-EXT" ) ) );

		return null;
	}

	@Override
	public Object destroy()
	        throws Exception
	{
		super.destroy();

		return null;
	}

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

			RadiusPacket request = radiusClient.createModifyRequest();
			prepareRequest( request, connection );

			//Выключение сервиса. Отправляем атрибуты редиректа
			request.addAttributes( disableRadiusAttributes );

			logger.info( "Send CoA lock: \n" + request );

			return radiusClient.sendAsync( request );
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
		}

		// закрываем все сервисы
		RadiusPacket request = radiusClient.createModifyRequest();
		prepareRequest( request, connection );
		request.addAttributes( serviceCloseAttributes );

		logger.info( "Send CoA: \n" + request );

		radiusClient.send( request );

		request = radiusClient.createModifyRequest();
		prepareRequest( request, connection );
		
		final String realm = e.getRealm();

		// открываем все подключенные сервисы
		for( Integer option : e.getNewOptions() )
		{
			RadiusAttributeSet set = optionRadiusAttributesMap.get( realm, option );
			if( set != null )
			{
				request.addAttributes( set );
			}
		}

		logger.info( "Send CoA: \n" + request );

		return radiusClient.sendAsync( request );
	}

	/**
	 * Сброс соединения PoD пакетом.<br/>
	 * {@inheritDoc}
	 */
	@Override
	public Object connectionClose( ServiceActivatorEvent e )
	        throws Exception
	{
		logger.info( "Connection close" );

		InetConnection connection = e.getConnection();

		RadiusPacket request = radiusClient.createDisconnectRequest();
		prepareRequest( request, connection );

		logger.info( "Send PoD: \n" + request );

		return radiusClient.sendAsync( request );
	}
}