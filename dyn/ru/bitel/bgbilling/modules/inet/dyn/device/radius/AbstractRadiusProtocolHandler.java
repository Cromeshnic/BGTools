package ru.bitel.bgbilling.modules.inet.dyn.device.radius;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.container.managed.ServerContext;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSet;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSetRealmMap;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.kernel.network.radius.RadiusProtocolHandler;
import ru.bitel.bgbilling.modules.inet.access.sa.ProtocolHandlerAdapter;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServType;
import ru.bitel.bgbilling.modules.inet.api.server.InetUtils;
import ru.bitel.bgbilling.modules.inet.radius.InetRadiusProcessor;
import ru.bitel.bgbilling.modules.inet.radius.RadiusAccessRequestHandler;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;
import ru.bitel.common.sql.ConnectionSet;

public class AbstractRadiusProtocolHandler
    extends ProtocolHandlerAdapter
    implements RadiusProtocolHandler
{
	private static final Logger logger = Logger.getLogger( AbstractRadiusProtocolHandler.class );

	private final int defaultRadiusVendor;

	/**
	 * Код вендора
	 */
	protected int radiusVendor;

	/**
	 * Набор атрибутов, присутствие которых в аккаунтинг-пакете означает что соединение в состоянии {@link InetServ#STATE_DISABLE}.
	 */
	protected RadiusAttributeSet disablePatternAttributes;
	
	/**
	 * Вендор атрибута - MAC-адрес
	 */
	protected int macAddressVendor;
	
	/**
	 * Код атрибута - MAC-адрес
	 */
	protected int macAddressType;
	
	/**
	 * Префикс атрибута (если есть) - MAC-адрес
	 */
	protected String macAddressPrefix;
	
	/**
	 * Привязка атрибутов к опциям модуля inet из конфига модуля:
	 * <code><pre>radius.inetOption.1.attributes=mpd-limit=out#1=all shape 256000 pass;mpd-limit=in#1=all rate-limit 10000000 pass
	 *radius.inetOption.2.attributes=mpd-limit=out#1=all shape 512000 pass;mpd-limit=in#1=all rate-limit 10000000 pass</pre></code>
	 */
	protected RadiusAttributeSetRealmMap optionRadiusAttributesMap;

	public AbstractRadiusProtocolHandler( int defaultRadiusVendor )
	{
		this.defaultRadiusVendor = defaultRadiusVendor;
	}

	public AbstractRadiusProtocolHandler()
	{
		this.defaultRadiusVendor = 2352; // Redback
	}

	@Override
	public void init( Setup setup, int moduleId, InetDevice inetDevice, InetDeviceType inetDeviceType, ParameterMap deviceConfig )
	    throws Exception
	{
		radiusVendor = deviceConfig.getInt( "radius.vendor", this.defaultRadiusVendor );

		String disablePatternAttributesString = deviceConfig.get( "radius.disable.pattern.attributes", null );
		if( Utils.notBlankString( disablePatternAttributesString ) )
		{
			disablePatternAttributes = RadiusAttributeSet.newRadiusAttributeSet( disablePatternAttributesString );
			// deviceConfig.getInt( "radius.realm.disable.attribute.type", -1 ); //  HTTP-Redirect-Profile-Name  107 =  NOAUTH	
		}
		else
		{
			disablePatternAttributes = null;
		}
		
		macAddressVendor = deviceConfig.getInt( "radius.macAddress.vendor", radiusVendor );
		macAddressType = deviceConfig.getInt( "radius.macAddress.type", -1 );
		macAddressPrefix = deviceConfig.get( "radius.macAddress.prefix", null );
		
		optionRadiusAttributesMap = InetUtils.newRadiusAttributeSetRealmMap( moduleId, deviceConfig, "radius.inetOption." );
	}

	/**
	 * Установка состояние соединения по присутствии в Accounting пакете определенных атрибутов.
	 * @param request
	 * @see #disablePatternAttributes
	 */
	protected void setStateFromAttributes( final RadiusPacket request )
	{
		if( disablePatternAttributes != null )
		{
			// если пакет содержит атррибуты - значит соединение в состоянии disable
			if( request.contains( disablePatternAttributes ) )
			{
				logger.debug( "State is disable (from attributes)" );
				request.setOption( InetRadiusProcessor.DEVICE_STATE, InetServ.STATE_DISABLE );
			}
			// иначе - enable
			else
			{
				logger.debug( "State is enable (from attributes)" );
				request.setOption( InetRadiusProcessor.DEVICE_STATE, InetServ.STATE_ENABLE );
			}
		}
	}
	
	/**
	 * Установка MAC-адреса из RADIUS-атрибута.
	 * @param request
	 */
	protected void setMacAddress( final RadiusPacket request )
	{
		final Object macAddress = getAttributeValue( request, macAddressVendor, macAddressType, macAddressPrefix );
		if( macAddress != null )
		{
			if( macAddress instanceof String )
			{
				request.setOption( InetRadiusProcessor.MAC_ADDRESS, (String)macAddress );
			}
			else if( macAddress instanceof byte[] )
			{
				request.setOption( InetRadiusProcessor.MAC_ADDRESS_BYTES, (byte[])macAddress );
			}
			else
			{
				logger.error( "Unknown type for macAddress attribute." );
			}
		}
	}
	
	/**
	 * Получение значения атрибута с учетом префикса, если установлен.
	 * @param request
	 * @param vendor
	 * @param type
	 * @param prefix необязательный префикс
	 * @return
	 */
	protected Object getAttributeValue( final RadiusPacket request, final int vendor, final int type, final String prefix )
	{
		if( vendor < -1 || type <= 0 )
		{
			return null;
		}

		// если префикса нет - берем значение просто из атрибута
		if( Utils.isBlankString( prefix ) )
		{
			final RadiusAttribute<?> ra = request.getAttribute( vendor, type );
			if( ra != null )
			{
				return ra.getValue();
			}
		}
		// если же есть префикс - проходим все атрибуты с указанным вендором и типом, ищем нужный префикс
		else
		{
			final List<RadiusAttribute<?>> ras = request.getAttributes( vendor, type );
			if( ras == null )
			{
				return null;
			}

			for( int i = 0, size = ras.size(); i < size; i++ )
			{
				final RadiusAttribute<?> ra = ras.get( i );

				final Object value = ra.getValue();
				if( value instanceof String )
				{
					final String stringValue = (String)value;
					if( stringValue.startsWith( prefix ) )
					{
						return stringValue.substring( prefix.length() );
					}
				}
			}
		}

		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void preprocessAccessRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
	    throws Exception
	{
		// устанавливаем MAC-адрес
		setMacAddress( request );
		// устанавливаем состояние по наличию определенных атрибутов
		setStateFromAttributes( request );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void preprocessAccountingRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
	    throws Exception
	{
		// устанавливаем MAC-адрес
		setMacAddress( request );
		// устанавливаем состояние по наличию определенных атрибутов
		setStateFromAttributes( request );
	}

	/**
	 * Этот метод по умолчанию не вызывается, т.к. данный класс не имплиментирует интерфейс {@link RadiusAccessRequestHandler}.
	 * Однако он содержит стандартную логику выдачи атрибутов, чтобы ее можно было изменить.<br/>
	 * Также, для того чтобы отработала логика выдачи по умолчанию достаточно вернуть как результат false.
	 * @see RadiusAccessRequestHandler#addResponseAttributes(ServerContext, InetServType, InetServ, RadiusAttributeSet, String, Map, RadiusAttributeSet, Set)
	 * @see RadiusAccessRequestHandler
	 *
	 * @param context
	 * @param inetServType
	 * @param inetServ
	 * @param response
	 * @param realm
	 * @param realmAttributeMap
	 * @param inetServAttributes
	 * @param optionSet
	 * @return true - если все атрибуты выданы в этом методе, т.е. добавлять атрибуты логикой по умолчанию не нужно,
	 * false - чтобы сработала логика выдачи атрибутов по умолчанию.  
	 * @throws Exception
	 */
	public boolean addResponseAttributes( ServerContext context, InetServType inetServType, InetServ inetServ, RadiusPacket response, String realm,
	                                      Map<String, RadiusAttributeSet> realmAttributeMap, RadiusAttributeSet inetServAttributes, Set<Integer> optionSet )
	    throws Exception
	{
		// атрибуты реалма
		/*if( InetRadiusProcessor.REALM_DISABLE.equals( realm ) )
		{
			RadiusAttributeSet set = disableAttributeMap.get( radiusSession.errorCode );
			if( set == null )
			{
				set = realmAttributeMap.get( realm );
			}

			if( set != null )
			{
				response.addAttributes( set );
			}
		}
		else
		{
			RadiusAttributeSet set = realmAttributeMap.get( realm );
			if( set != null )
			{
				response.addAttributes( set );
			}
		}*/
		
		// атрибуты реалма
		RadiusAttributeSet realmAttributeSet = realmAttributeMap.get( realm );
		if( realmAttributeSet != null )
		{
			response.addAttributes( realmAttributeSet );
		}

		// атрибуты сервиса/типа сервиса
		if( inetServAttributes != null )
		{
			response.addAttributes( inetServAttributes );
		}

		// для disable не выдаем атрибуты опций
		if( InetRadiusProcessor.REALM_DISABLE.equals( realm ) )
		{
			return true;
		}

		// атрибуты опций
		if( optionSet != null && optionSet.size() > 0 )
		{
			for( Integer option : optionSet )
			{
				final RadiusAttributeSet optionRadiusAttributes = this.optionRadiusAttributesMap.get( realm, option );
				if( optionRadiusAttributes != null )
				{
					response.addAttributes( optionRadiusAttributes );
				}
			}
		}

		return true;
	}
}