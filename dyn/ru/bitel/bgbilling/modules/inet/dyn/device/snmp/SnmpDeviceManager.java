package ru.bitel.bgbilling.modules.inet.dyn.device.snmp;

import java.util.List;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.network.snmp.SnmpClient;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;
import ru.bitel.oss.systems.inventory.resource.common.bean.Device;
import ru.bitel.oss.systems.inventory.resource.common.bean.DeviceType;
import ru.bitel.oss.systems.inventory.resource.server.DeviceManager;
import ru.bitel.oss.systems.inventory.resource.server.DeviceManagerAdapter;
import uk.co.westhawk.snmp.stack.AsnObjectId;

/**
 * Реализация {@link DeviceManager}, использующая SNMP для работы у устройством.
 * Данный обработчик можно расширить для выполнения команд с закладки Устройства модуля.<br/>Например:
 * <pre>public class MySnmpDeviceManager
 *        extends SnmpDeviceManager
 *        implements DeviceManager
 *{
 *	public Boolean reboot()
 *	{
 *		return snmpClient.set( rebootOid, -1, 1L );
 *	}
 *}</pre>
 * @see DeviceManager
 * @author amir
 */
public class SnmpDeviceManager
        extends DeviceManagerAdapter
        implements DeviceManager
{
	private static final Logger logger = Logger.getLogger( SnmpDeviceManager.class );

	String host;

	int snmpVersion;
	int snmpPort;
	String snmpCommunity;

	protected SnmpClient snmpClient;

	long[] uptimeOid;

	@Override
	public Object init( Setup setup, int moduleId, Device<?, ?> device, DeviceType deviceType, ParameterMap deviceConfig )
	{
		logger.info( "INIT" );
		
		final List<String[]> hosts = device.getHostsAsString();
		final String[] host = (hosts != null && hosts.size() > 0) ? hosts.get( 0 ) : null;

		this.snmpVersion = deviceConfig.getInt( "snmp.version", 1 );
		this.host = deviceConfig.get( "snmp.host", host != null ? host[0] : device.getHost() );
		this.snmpPort = deviceConfig.getInt( "snmp.port", Utils.parseInt( host != null ? host[1] : "161" ) );
		this.snmpCommunity = deviceConfig.get( "snmp.community", device.getSecret() );

		this.uptimeOid = new AsnObjectId( deviceConfig.get( "snmp.uptimeOid", "1.3.6.1.2.1.1.3.0" ) ).getOid();

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

	@Override
	public Object uptime()
	        throws Exception
	{
		return snmpClient.get( uptimeOid, -1, Long.class );
	}
}
