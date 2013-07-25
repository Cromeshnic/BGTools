package ru.bitel.bgbilling.modules.inet.dyn.device.mikrotik;

import java.util.Set;

import org.apache.log4j.Logger;

import bitel.billing.server.card.bean.SuperCardModule;
import bitel.billing.server.util.mikrotik.MikrotikApiSession;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.dyn.device.terminal.AbstractTerminalServiceActivator;
import ru.bitel.bgbilling.modules.inet.dyn.device.terminal.SSHServiceActivator;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;

public class MikrotikServiceActivator
    extends AbstractTerminalServiceActivator
    implements ServiceActivator
{
	private MikrotikApiSession session;
	private static final Logger logger = Logger.getLogger( SSHServiceActivator.class );
	
	@Override
    public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap config )
        throws Exception
    {
	    // TODO Auto-generated method stub
	    return super.init( setup, moduleId, device, deviceType, config );
    }

	@Override
    public Object connect()
        throws Exception
    {
		session = new MikrotikApiSession( host, port, username, password );
		session.connect();
		return null;
    }

	@Override
    public Object disconnect()
        throws Exception
    {
	    super.disconnect();
	    session.doCommand( this.exitCommand );
	    session.disconnect();
	    logger.debug( "Disconnected" );
	    return null;
    }
	
	@Override
	protected void executeCommand( String command )
	    throws Exception
	{
		logger.info( "execute: " + command );	
		command = command.replace( "\\n", "\n" );		
		String result = session.doCommand( command );
		logger.info( "result=" + result );
	}
	
	@Override
	protected Object getValue( ServiceActivatorEvent e, InetServ serv, InetConnection connection, Set<Integer> options, String macros, Object[] args, Object[] globalArgs )
	    throws Exception
	{			
		if( "servId".equals( macros ) )
		{
			return String.valueOf( serv.getId() );		
		}			
		else
		{
			return super.getValue( e, serv, connection, options, macros, args, globalArgs );
		}
	}
}
