package ru.bitel.bgbilling.modules.inet.dyn.device.wifi;
import java.net.DatagramSocket;
import java.util.Date;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.wifi.common.IpInfo;
import ru.bitel.bgbilling.kernel.wifi.common.WiFiPacket;
import ru.bitel.bgbilling.kernel.wifi.common.WiFiUtil;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorAdapter;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.AccessCodes;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.inet.IpAddress;
import bitel.billing.common.IPUtils;


public class WiFiServiceActivator
 extends ServiceActivatorAdapter
    implements ServiceActivator
{
	private static Logger logger = Logger.getLogger( ServiceActivatorAdapter.class );
	
	private DatagramSocket socket = null;
	private InetDevice device;

	private String host;
	private int port;
	// private byte[] secret;
	private String secret;

	/**
	 * Инициализация обработчика. Вызывается после создания объекта.
	 * @param setup
	 * @param moduleId
	 * @param device
	 * @param deviceType
	 * @param config
	 * @return
	 * @throws Exception
	 */
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap config )
	    throws Exception
	{
		this.device = device;
		
		this.secret = device.getSecret();

		if( device.getHosts().size() > 0 )
		{
			this.host = device.getHosts().get(0).getHostName();	
			this.port = device.getHosts().get(0).getPort();
		}
		else
		{
			return false;
		}

		return true;
	}

	

	/**
	 * Подключение к устройству для работы с ним.
	 * @return
	 * @throws Exception
	 */
	public Object connect()
	    throws Exception
	{
		socket = new DatagramSocket();
		socket.setSoTimeout( 3000 );
		
		return true;
	}

	/**
	 * Отключение от устройства.
	 * @return
	 * @throws Exception
	 */
	public Object disconnect()
	    throws Exception
	{
		socket.close();
		return true;
	}
	/**

	/**
	 * Закрытие (принудительное) соединения.<br/>
	 * Обычно вызывается при {@link AccessCodes#TOO_MANY_SESSIONS_ERROR} или из метода {@link #connectionModify(ServiceActivatorEvent)}
	 * @param e
	 * @return
	 * @throws Exception
	 */
	public Object connectionClose( ServiceActivatorEvent e )
	    throws Exception
	{	
		logger.debug( "connection close" );
		InetConnection connection = e.getConnection();
		
		IpInfo ipInfo = null;

		WiFiPacket packetOut = new WiFiPacket();
		packetOut.setSecret( this.secret );
		packetOut.setType( WiFiPacket.TYPE_KILL );

		int ip = IPUtils.convertStringIPtoInt( IpAddress.toString( connection.getInetAddressBytes() ) );

		packetOut.add( ip, new Date().getTime() );
		WiFiUtil.sendPacket( socket, packetOut, host, port );

		WiFiPacket packetIn = WiFiUtil.recievePacket( socket, secret );
		if( packetIn != null )
		{
			ipInfo = packetIn.getFirstInfo();
		}
		

		// если вернули то же ip занчит все в порядке - убили ip
		return ipInfo != null && ipInfo.getIp() == ip;
		
	}



	@Override
    public Object connectionModify( ServiceActivatorEvent e )
        throws Exception
    {
		logger.debug( "connection modify" );
		if( e.getNewState() == InetServ.STATE_DISABLE )
		{
			return connectionClose( e );
		}
		
		return true;
    }
}
