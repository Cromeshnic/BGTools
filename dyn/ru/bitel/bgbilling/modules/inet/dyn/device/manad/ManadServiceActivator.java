package ru.bitel.bgbilling.modules.inet.dyn.device.manad;

import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.dyn.device.terminal.AbstractTerminalServiceActivator;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;

public class ManadServiceActivator extends AbstractTerminalServiceActivator
    
    implements ServiceActivator
{
	private static Logger logger = Logger.getLogger( ManadServiceActivator.class );
	
	private InetDevice inetDevice;
	
	private Socket socket;
	PrintWriter out;
	@Override
    public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap config )
        throws Exception
    {
	    super.init( setup, moduleId, device, deviceType, config );
		this.inetDevice = device;
	    return null;
    }	
	
	@Override
    public Object connect()
        throws Exception
    {
		List<InetSocketAddress> hosts = inetDevice.getHosts();
		String host = hosts.get( 0 ).getHostName();
		int port = hosts.get( 0 ).getPort();
 		
		socket = new Socket( host, port );
		out = new PrintWriter( socket.getOutputStream(), true );
		
		logger.info( "Connected" );
		
		return super.connect();
    }

	@Override
    public Object disconnect()
        throws Exception
    {
		super.disconnect();
		out.close();
		socket.close();
		
		logger.info( "Disconnected" );
		return null;
    }

	

	@Override
    protected void executeCommand( String command )
        throws Exception
    { 
		logger.info( "execute: " + command );
		out.println( command );
    }

}
