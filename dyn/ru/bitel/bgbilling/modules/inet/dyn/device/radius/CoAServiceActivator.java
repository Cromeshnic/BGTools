package ru.bitel.bgbilling.modules.inet.dyn.device.radius;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSet;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;

/**
 * Обработчик активации сервисов, полностью реализующий функционал PodNasConnectionInspector модуля dialup.
 */
public class CoAServiceActivator
    extends AbstractRadiusServiceActivator
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( CoAServiceActivator.class );

	public CoAServiceActivator()
	{
		super( null, false, "NAS-Port, Acct-Session-Id, User-Name, Framed-IP-Address, NAS-IP-Address, NAS-Identifier", true );
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

			RadiusPacket request = radiusClient.createModifyRequest();
			prepareRequest( request, connection );

			request.addAttributes( disableRadiusAttributes );

			// устанавливаем флаг, что нужно будет поменять состояние соединения в базе
			if( needConnectionStateModify )
			{
				e.setConnectionStateModified( true );
			}

			logger.info( "Send CoA lock: \n" + request );

			return radiusClient.sendAsync( request );
		}

		if( e.getOldState() == InetServ.STATE_DISABLE )
		{
			if( !withoutBreak )
			{
				return connectionClose( e );
			}
		}
		
		RadiusPacket request = radiusClient.createModifyRequest();
		prepareRequest( request, connection );
		
		final String realm = e.getRealm();

		if( e.getOldState() == InetServ.STATE_DISABLE )
		{
			// дополнительные атрибуты при отмене ограничений (при withoutBreak=true)
		}

		// для активных опций модуля inet просто добавляем привязанные атрибуты
		// (подразумевается что параметры будут просто переопределены/перетерты)
		for( Integer option : e.getNewOptions() )
		{
			RadiusAttributeSet set = optionRadiusAttributesMap.get( realm, option );
			if( set != null )
			{
				request.addAttributes( set );
			}
		}
		
		// устанавливаем флаг, что нужно будет поменять состояние соединения в базе
		if( needConnectionStateModify )
		{
			e.setConnectionStateModified( true );
		}

		logger.info( "Send CoA: \n" + request );

		return radiusClient.sendAsync( request );
	}

	/**
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
