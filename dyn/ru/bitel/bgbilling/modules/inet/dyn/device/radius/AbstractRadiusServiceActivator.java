package ru.bitel.bgbilling.modules.inet.dyn.device.radius;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSet;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttributeSetRealmMap;
import ru.bitel.bgbilling.kernel.network.radius.RadiusClient;
import ru.bitel.bgbilling.kernel.network.radius.RadiusDictionary;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.kernel.network.radius.nas.Nas;
import ru.bitel.bgbilling.kernel.network.radius.nas.NasList;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorAdapter;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.server.InetUtils;
import ru.bitel.bgbilling.modules.inet.radius.RadiusHourlyDataLogger;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;

public abstract class AbstractRadiusServiceActivator
    extends ServiceActivatorAdapter
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( AbstractRadiusServiceActivator.class );
	
	/**
	 * Класс, записывающий radius пакеты в файлы логов. Нужен для логирования CoA и PoD.
	 */
	@Resource( name = "radiusDataLogger" )
	protected RadiusHourlyDataLogger dataLogger;

	/**
	 * Код устройства
	 */
	protected int deviceId;

	/**
	 * IP адрес наса.
	 */
	protected InetAddress nasHostAddr;

	/**
	 * Идентификатор наса
	 */
	protected String nasIdentifier;

	/**
	 * Radius-клиент
	 */
	protected RadiusClient radiusClient;

	/**
	 * Привязка атрибутов к реалму
	 */
	protected Map<String, RadiusAttributeSet> realmRadiusAttributesMap;

	/**
	 * Привязка атрибутов к опциям модуля inet из конфига модуля:
	 * <code><pre>radius.inetOption.1.attributes=mpd-limit=out#1=all shape 256000 pass;mpd-limit=in#1=all rate-limit 10000000 pass
	 *radius.inetOption.2.attributes=mpd-limit=out#1=all shape 512000 pass;mpd-limit=in#1=all rate-limit 10000000 pass</pre></code>
	 */
	protected RadiusAttributeSetRealmMap optionRadiusAttributesMap;

	/**
	 * Атрибуты для установки ограничений при посылке CoA вместо PoD, например, ограничение скорости:
	 * <code><pre>radius.realm.disable.attributes=mpd-limit=out#1=all shape 60000 pass;mpd-limit=in#1=all rate-limit 10000000 pass</pre></code>
	 * @see #withoutBreak
	 */
	protected RadiusAttributeSet disableRadiusAttributes;

	/**
	 * Соединения без разрывов, т.е. посылать CoA вместо PoD с ограничениями.
	 */
	protected boolean withoutBreak;

	/**
	 * Имена атрибутов, связанные с соединением, которые нужно посылать в CoA и PoD. 
	 */
	protected Set<String> connectionRadiusAttributes;

	/**
	 * Добавлять ли атрибуты из реалма.
	 */
	protected boolean addRealmAttributes;

	/**
	 * Фиксированные атрибуты, которые нужно посылать в PoD.
	 */
	protected RadiusAttributeSet podFixedAttributes;

	/**
	 * Фиксированные атрибуты, которые нужно посылать в CoA.
	 */
	protected RadiusAttributeSet coaFixedAttributes;

	/**
	 * Нужно ли после смены состояния соединения сразу менять состояние в базе.<br/>
	 * Нет - если состояние меняется в обработчике протокола процессора при аккаунтинге.
	 */
	protected boolean needConnectionStateModify;

	private final String defaultOptionsPrefix;

	/**
	 * @see #withoutBreak
	 */
	private final boolean defaultWithoutBreak;

	/**
	 * @see #connectionRadiusAttributes
	 */
	private final String defaultConnectionAttributes;

	/**
	 * @see #needConnectionStateModify
	 * @see ServiceActivatorEvent#setConnectionStateModified(boolean)
	 */
	private final boolean defaultNeedConnectionStateModify;
	
	private boolean logCoA;
	private boolean logPoD;

	public AbstractRadiusServiceActivator( String optionsPrefix, boolean defaultWithoutBreak, String defaultConnectionAttributes, boolean defaultNeedConnectionStateModify )
	{
		if( optionsPrefix == null )
		{
			optionsPrefix = "radius.inetOption.";
		}

		this.defaultOptionsPrefix = optionsPrefix;
		this.defaultWithoutBreak = defaultWithoutBreak;
		this.defaultConnectionAttributes = defaultConnectionAttributes;
		this.defaultNeedConnectionStateModify = defaultNeedConnectionStateModify;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceConfig )
	    throws Exception
	{
		this.deviceId = device.getId();

		final List<String[]> hosts = device.getHostsAsString();
		final String[] host = (hosts != null && hosts.size() > 0) ? hosts.get( 0 ) : null;

		String nasHost = deviceConfig.get( "radius.host", host != null ? host[0] : device.getHost() );
		this.nasHostAddr = InetAddress.getByName( nasHost );

		int nasPort = deviceConfig.getInt( "radius.port", Utils.parseInt( host != null ? host[1] : "1700" ) );
		
		String sourceHost = deviceConfig.get( "sa.radius.sourceHost", deviceConfig.get( "radius.sourceHost", null ) );
		int sourcePort = deviceConfig.getInt( "sa.radius.sourcePort", deviceConfig.getInt( "radius.sourcePort", -1 ) );
		
		nasIdentifier = deviceConfig.get( "radius.identifier", device.getIdentifier() );

		byte[] nasSecret = deviceConfig.get( "radius.secret", device.getSecret() ).getBytes();

		int log = deviceConfig.getInt( "sa.radius.log", 0 );
		logCoA = deviceConfig.getInt( "sa.radius.log.coa", log ) > 0;
		logPoD = deviceConfig.getInt( "sa.radius.log.pod", log ) > 0;

		if( logCoA || logPoD )
		{
			radiusClient = new RadiusClient( sourceHost, sourcePort, nasHostAddr, nasPort, nasSecret, setup, dataLogger, logCoA, logPoD );
		}
		else
		{
			radiusClient = new RadiusClient( sourceHost, sourcePort, nasHostAddr, nasPort, nasSecret );
		}

		realmRadiusAttributesMap = RadiusAttributeSet.newRadiusAttributeRealmMap( deviceConfig, "radius.realm.", "attributes" );
		// optionRadiusAttributesMap = RadiusAttributeSet.newRadiusAttributeSetRealmMap( deviceConfig, deviceConfig.get( "sa.radius.option.attributesPrefix", defaultOptionsPrefix ), "attributes" );
		optionRadiusAttributesMap = InetUtils.newRadiusAttributeSetRealmMap( moduleId, deviceConfig, deviceConfig.get( "sa.radius.option.attributesPrefix", defaultOptionsPrefix ) );

		// атрибуты для отправки CoA, если нужно прекратить доступ
		String disableRadiusAttributesString = deviceConfig.get( "sa.radius.disable.attributes", deviceConfig.get( "radius.disable.attributes", deviceConfig.get( "radius.realm.disable.attributes", null ) ) );
		disableRadiusAttributes = RadiusAttributeSet.newRadiusAttributeSet( disableRadiusAttributesString );

		// если нужно прекратить доступ - отправлять PoD или CoA
		withoutBreak = deviceConfig.getInt( "sa.radius.connection.withoutBreak", defaultWithoutBreak ? 1 : 0 ) > 0;
		if( withoutBreak && Utils.isBlankString( disableRadiusAttributesString ) )
		{
			logger.warn( "withoutBreak=true but disableRadiusAttributes is empty!" );
			withoutBreak = false;
		}

		connectionRadiusAttributes = new HashSet<String>( Arrays.asList( deviceConfig.get( "sa.radius.connection.attributes", defaultConnectionAttributes ).split( "\\s*,\\s*" ) ) );

		podFixedAttributes = RadiusAttributeSet.newRadiusAttributeSet( deviceConfig.get( "sa.radius.pod.attributes", null ) );
		coaFixedAttributes = RadiusAttributeSet.newRadiusAttributeSet( deviceConfig.get( "sa.radius.coa.attributes", null ) );

		addRealmAttributes = deviceConfig.getInt( "sa.radius.realm.addAttributes", 0 ) > 0;

		needConnectionStateModify = deviceConfig.getInt( "sa.radius.connection.stateModify", defaultNeedConnectionStateModify ? 1 : 0 ) > 0;

		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object destroy()
	    throws Exception
	{
		radiusClient.destroy();

		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object connect()
		throws Exception
	{
		if( logCoA || logPoD )
		{
			final Nas<?, ?, ?> nas = NasList.getInstance().get( deviceId );
			radiusClient.setNas( nas );
		}

		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object disconnect()
		throws Exception
	{
		return null;
	}

	/**
	 * Подготовка запроса - установка атрибутов, связанная с соединением и насом, а также фиксированных атрибутов.
	 * @param request
	 * @param connection
	 * @see #connectionRadiusAttributes
	 * @see #coaFixedAttributes
	 * @see #podFixedAttributes
	 */
	protected void prepareRequest( RadiusPacket request, InetConnection connection )
	{
		radiusClient.setConnectionId( connection.getId() );
		
		if( connectionRadiusAttributes.size() == 0 || connectionRadiusAttributes.contains( "NAS-Port" ) )
		{
			request.setAttribute( new RadiusAttribute<Integer>( -1, RadiusDictionary.NAS_Port, 0, connection.getDevicePort() ) );
		}

		if( connectionRadiusAttributes.size() == 0 || connectionRadiusAttributes.contains( "Acct-Session-Id" ) )
		{
			request.setAttribute( new RadiusAttribute<String>( -1, RadiusDictionary.Acct_Session_Id, 0, connection.getAcctSessionId() ) );
		}

		if( connectionRadiusAttributes.size() == 0 || connectionRadiusAttributes.contains( "User-Name" ) )
		{
			request.setAttribute( new RadiusAttribute<String>( -1, RadiusDictionary.User_Name, 0, connection.getUsername() ) );
		}

		if( connectionRadiusAttributes.size() == 0 || connectionRadiusAttributes.contains( "Framed-IP-Address" ) )
		{
			if(connection.getInetAddressBytes()!=null)
			{
			request.setAttribute( new RadiusAttribute<Integer>( -1, RadiusDictionary.Framed_IP_Address, 0, ByteBuffer.wrap( connection.getInetAddressBytes() ) ) );
			}
		}

		if( connectionRadiusAttributes.size() == 0 || connectionRadiusAttributes.contains( "NAS-IP-Address" ) )
		{
			int address = Utils.convertBytesToInt( nasHostAddr.getAddress() );
			if( address != 0 )
			{
				request.setAttribute( new RadiusAttribute<Integer>( -1, RadiusDictionary.NAS_IP_Address, 0, address ) );
			}
		}

		if( connectionRadiusAttributes.size() == 0 || connectionRadiusAttributes.contains( "NAS-Identifier" ) )
		{
			request.setAttribute( new RadiusAttribute.RadiusAttributeString( -1, RadiusDictionary.NAS_Identifier, nasIdentifier ) );
		}

		if( request.getCode() == RadiusPacket.CoA_REQUEST )
		{
			// если есть фиксированные атрибуты - добавляем их
			if( coaFixedAttributes != null )
			{
				request.addAttributes( coaFixedAttributes );
			}

			// если нужно добавить атрибуты реалма - добавляем
			if( addRealmAttributes )
			{
				String[] usernameAndRealm = getUsernameAndRealm( connection.getUsername(), true );
				String realm = usernameAndRealm[1];
				if( Utils.isBlankString( realm ) )
				{
					realm = "default";
				}

				RadiusAttributeSet set = realmRadiusAttributesMap.get( realm );
				if( set != null )
				{
					request.addAttributes( set );
				}
			}
		}
		else if( request.getCode() == RadiusPacket.DISCONNECT_REQUEST )
		{
			// если есть фиксированные атрибуты - добавляем их
			if( podFixedAttributes != null )
			{
				request.addAttributes( podFixedAttributes );
			}
		}
	}

	private static String[] getUsernameAndRealm( String username, boolean usernameRemoveDomain )
	{
		String[] result = new String[2];

		int pos = username.indexOf( '@' );
		if( pos > 0 )
		{
			result[0] = username.substring( pos );
			result[1] = username.substring( pos + 1 );
		}
		else
		{
			result[0] = username;
			result[1] = "default";
		}

		if( usernameRemoveDomain )
		{
			pos = result[0].indexOf( '\\' );
			if( pos >= 0 )
			{
				result[0] = result[0].substring( pos + 1 );
			}
		}

		return result;
	}
}