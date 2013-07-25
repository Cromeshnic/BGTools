package ru.bitel.bgbilling.modules.inet.dyn.device.redback;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.event.EventProcessor;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSet;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.modules.inet.access.InetConnectionManager;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;

public class SmartEdgeClipsServiceActivator
    extends SmartEdgeServiceActivator
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( SmartEdgeServiceActivator.class );

	/**
	 * Нужно ли посылать CoA при переводе из disable в enable (при withoutBreak=false)
	 */
	private boolean coaOnEnable;

	public SmartEdgeClipsServiceActivator()
	{
		super( false );// по умолчанию сбрасываем соединение, чтобы переполучить ip адрес
	}

	@Override
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceConfig )
	    throws Exception
	{
		super.init( setup, moduleId, device, deviceType, deviceConfig );

		this.coaOnEnable = deviceConfig.getInt( "sa.radius.connection.coa.onEnable", 0 ) > 0;

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

			return connectionDisable( connection );
		}

		if( e.getOldState() == InetServ.STATE_DISABLE )
		{
			if( !withoutBreak )
			{
				// убрать из DHCP, чтобы выдало NaK
				EventProcessor.getInstance().request( new InetConnectionManager.ConnectionRemoveEvent( connection ) );

				if( !coaOnEnable )
				{
					return null;
				}
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
	 * Ограничение доступа 
	 */
	private Object connectionDisable( InetConnection connection )
	        throws Exception
	{
		logger.info( "Connection disable" );

		RadiusPacket request = radiusClient.createModifyRequest();
		prepareRequest( request, connection );

		//Выключение сервиса. Отправляем атрибуты редиректа
		request.addAttributes( disableRadiusAttributes );

		logger.info( "Send CoA lock: \n" + request );

		return radiusClient.sendAsync( request );
	}

	/**
	 * Ограничение доступа и 
	 */
	@Override
	public Object connectionClose( ServiceActivatorEvent event )
	        throws Exception
	{
		logger.info( "Connection close" );

		InetConnection connection = event.getConnection();

		// убрать из DHCP, чтобы выдало NaK
		EventProcessor.getInstance().request( new InetConnectionManager.ConnectionRemoveEvent( connection ) );

		return connectionDisable( connection );
	}
}