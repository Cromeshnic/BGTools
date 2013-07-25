package ru.bitel.bgbilling.modules.inet.dyn.device.snmp;

import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.network.snmp.SnmpClient;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorAdapter;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;
import uk.co.westhawk.snmp.stack.AsnObjectId;

/*
 * sa.snmp.disconnect.mode=
 * sa.snmp.disconnect.oid=
 * sa.snmp.disconnect.value=
 * sa.snmp.connection.key.field=
 * sa.snmp.connection.key.offset=
 * sa.snmp.connection.key.length=
 * sa.snmp.connection.key.mode=
 */
/**
 * <b>Параметры</b><pre> sa.snmp.disconnect.mode=oid|value
 * sa.snmp.disconnect.oid=
 * sa.snmp.disconnect.value=1|RESET
 * sa.snmp.connection.key.field=nasPort|acctSessionId|ipAddress
 * sa.snmp.connection.key.offset=
 * sa.snmp.connection.key.length=
 * sa.snmp.connection.key.mode=plain|hex</pre>

 * <b>FreeBSD MPD 4.x, 5.x (vendor=12341)</b><pre> snmp.version=2
 * sa.snmp.disconnect.mode=value
 * sa.snmp.disconnect.oid=1.3.6.1.4.1.2021.255.1
 * #sa.snmp.connection.key.field=nasPort</pre>
 *
 * <b>Cisco 53x (vendor=9) либо другие модели Cisco</b><pre> snmp.version=2
 * sa.snmp.disconnect.mode=oid
 * sa.snmp.disconnect.oid=1.3.6.1.4.1.9.9.150.1.1.3.1.5
 * #sa.snmp.disconnect.value=1
 * sa.snmp.connection.key.field=acctSessionId
 * # возможна ситуация когда в Acct-Session-Id передаётся не только код сессии но и дополнительная "приставка" вначале
 * #sa.snmp.connection.key.offset=
 * #sa.snmp.connection.key.length=
 * sa.snmp.connection.key.mode=hex</pre>
 * 
 * <b>Cisco 36x (vendor=9)</b><pre> sa.snmp.disconnect.mode=value
 * sa.snmp.disconnect.oid=1.3.6.1.4.1.9.2.9.10.0 
 * #sa.snmp.connection.key.field=nasPort</pre>
 * 
 * <b>Lucent Acsend (vendor=529)</b><pre> sa.snmp.disconnect.mode=oid
 * sa.snmp.disconnect.oid=1.3.6.1.4.1.529.12.3.1.3
 * #sa.snmp.disconnect.value=1
 * sa.snmp.connection.key.field=acctSessionId
 * # закомментировать, если код сессии (Acct-Session-Id) приходит в десятичном формате
 * sa.snmp.connection.key.mode=hex</pre>
 * 
 * @author amir
 *
 */
public class SnmpServiceActivator
    extends ServiceActivatorAdapter
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( SnmpServiceActivator.class );

	String host;

	int snmpVersion;
	int snmpPort;
	String snmpCommunity;

	protected SnmpClient snmpClient;

	enum KeyField
	{
		nasPort, acctSessionId, ipAddress
	}

	enum KeyMode
	{
		plain, hex
	}

	enum DisconnectMode
	{
		oid, value
	}

	KeyField connectionKeyField;

	int connectionKeyOffset;
	int connectionKeyLength;

	KeyMode connectionKeyMode;

	DisconnectMode disconnectMode;
	long[] disconnectOid;
	Object disconnectValue;

	@Override
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceConfig )
	    throws Exception
	{
		logger.info( "INIT" );

		final List<String[]> hosts = device.getHostsAsString();
		final String[] host = (hosts != null && hosts.size() > 0) ? hosts.get( 0 ) : null;

		this.snmpVersion = deviceConfig.getInt( "snmp.version", 1 );
		this.host = deviceConfig.get( "snmp.host", host != null ? host[0] : device.getHost() );
		this.snmpPort = deviceConfig.getInt( "snmp.port", Utils.parseInt( host != null ? host[1] : "161" ) );
		this.snmpCommunity = deviceConfig.get( "snmp.community", device.getSecret() );

		this.disconnectMode = Utils.parseEnum( DisconnectMode.class, deviceConfig.get( "sa.snmp.disconnect.mode" ), DisconnectMode.oid );
		this.disconnectOid = new AsnObjectId( deviceConfig.get( "sa.snmp.disconnect.oid", "1.3.6.1.4.1.9.2.9.10.0" ) ).getOid();
		this.disconnectValue = deviceConfig.get( "sa.snmp.disconnect.value", "1" );
		int disconnectValue = Utils.parseInt( (String)this.disconnectValue, -1 );
		if( disconnectValue != -1 )
		{
			this.disconnectValue = disconnectValue;
		}

		this.connectionKeyField = Utils.parseEnum( KeyField.class, deviceConfig.get( "sa.snmp.connection.key.field" ), KeyField.nasPort );
		this.connectionKeyOffset = deviceConfig.getInt( "sa.snmp.connection.key.offset", 0 );
		this.connectionKeyLength = deviceConfig.getInt( "sa.snmp.connection.key.length", 0 );
		this.connectionKeyMode = Utils.parseEnum( KeyMode.class, deviceConfig.get( "sa.snmp.connection.key.mode" ), KeyMode.plain );

		return null;
	}

	@Override
	public Object destroy()
	    throws Exception
	{
		if( snmpClient != null )
		{
			snmpClient.destroy();
			snmpClient = null;
		}

		return null;
	}

	@Override
	public Object connect()
	    throws Exception
	{
		snmpClient = new SnmpClient( host, snmpVersion, snmpCommunity );

		return null;
	}

	@Override
	public Object disconnect()
	    throws Exception
	{
		if( snmpClient != null )
		{
			snmpClient.destroy();
			snmpClient = null;
		}

		return null;
	}

	private Object getKey( final InetConnection connection )
	{
		switch( connectionKeyField )
		{
			default:
			case nasPort:
			{
				return connection.getDevicePort();
			}

			case acctSessionId:
			{
				String key = connection.getAcctSessionId();
				if( connectionKeyOffset > 0 || connectionKeyLength > 0 )
				{
					int length = connectionKeyLength > 0 ? connectionKeyLength : (key.length() - connectionKeyOffset);
					if( key.length() > length )
					{
						key = key.substring( connectionKeyOffset, connectionKeyOffset + length );
					}
				}

				switch( connectionKeyMode )
				{
					default:
					case plain:
					{
						return Utils.parseLong( key, -1 );
					}

					case hex:
					{
						try
						{
							return Long.parseLong( key, 16 );
						}
						catch( Exception ex )
						{
							logger.error( ex.getMessage(), ex );
							return -1L;
						}
					}
				}
			}
			
			case ipAddress:
			{
				return connection.getInetAddressBytes();
			}
		}
	}

	private static long[] compose( long[] oid, Object id )
	{
		if( id instanceof Number )
		{
			long[] result = Arrays.copyOf( oid, oid.length + 1 );
			result[oid.length] = ((Number)id).longValue();
			return result;
		}
		else if( id instanceof byte[] )
		{
			byte[] array = (byte[])id;
			long[] result = Arrays.copyOf( oid, oid.length + array.length );

			for( int i = oid.length; i < result.length; i++ )
			{
				result[i] = (array[i - oid.length] & 0xff);
			}

			return result;
		}
		else if( id instanceof long[] )
		{
			long[] array = (long[])id;
			long[] result = Arrays.copyOf( oid, oid.length + array.length );

			System.arraycopy( array, 0, result, oid.length, array.length );

			return result;
		}
		else
		{
			return oid;
		}
	}

	@Override
	public Object connectionModify( ServiceActivatorEvent e )
	    throws Exception
	{
		logger.info( "Connection modify: oldState: " + e.getOldState() + "; newState: " + e.getNewState() + "; oldOptionSet: " + e.getOldOptions() + "; newOptionSet: " + e.getNewOptions() );

		return this.connectionClose( e );
	}

	@Override
	public Object connectionClose( ServiceActivatorEvent e )
	    throws Exception
	{
		logger.info( "Connection close" );

		final InetConnection connection = e.getConnection();

		switch( this.disconnectMode )
		{
			default:
			case oid:
			{
				final Object key = getKey( connection );

				logger.info( "Set " + disconnectValue + " to " + key );
				return snmpClient.setAsync( compose( disconnectOid, key ), -1, disconnectValue );
			}

			case value:
			{
				final Object key = getKey( connection );

				logger.info( "Set " + key );
				return snmpClient.setAsync( disconnectOid, -1, key );
			}
		}
	}
}
