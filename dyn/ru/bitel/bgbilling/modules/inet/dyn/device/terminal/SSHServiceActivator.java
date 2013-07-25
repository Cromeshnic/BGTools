package ru.bitel.bgbilling.modules.inet.dyn.device.terminal;

import java.util.Set;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;
import bitel.billing.server.util.ssh.SSHSession;

/**
 *  {@inheritDoc}
 *  @see AbstractTerminalServiceActivator
 */
public class SSHServiceActivator
    extends AbstractTerminalServiceActivator
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( SSHServiceActivator.class );

	/**
	 *  Regexp как признак выхода
	 */
	protected String regexp;

	protected String endSequence;

	protected SSHSession session;

	@Override
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap config )
	    throws Exception
	{
		super.init( setup, moduleId, device, deviceType, config );
		
		if( this.port <= 0 )
		{
			this.port = 22;
		}

		this.endSequence = config.get( "sa.endSequence", null );
		this.regexp = config.get( "sa.exitRegexp", null );

		return null;
	}

	@Override
	public Object destroy()
	    throws Exception
	{
		return super.destroy();
	}

	@Override
	public Object connect()
	    throws Exception
	{
		SSHSession session = new SSHSession( host, port, username, password );
		session.setTimeout( timeout );

		if( Utils.notBlankString( endSequence ) )
		{
			session.setEndString( endSequence );
		}

		if( Utils.notBlankString( regexp ) )
		{
			session.setRegexp( regexp );
		}

		session.connect();
		logger.info( "Connected" );
		
		this.session = session;

		return super.connect();
	}

	@Override
	public Object disconnect()
	    throws Exception
	{
		super.disconnect();

		session.doCommandAsync( this.exitCommand );
		session.disconnect();

		logger.debug( "Disconnected" );

		return null;
	}

	@Override
	protected void executeCommand( String command )
	    throws Exception
	{
		logger.info( "execute: " + command );
		logger.info( session.doCommand( command ) );
	}

	@Override
	protected Object getValue( ServiceActivatorEvent e, InetServ serv, InetConnection connection, Set<Integer> options, String macros, Object[] args, Object[] globalArgs )
	    throws Exception
	{
		if( "setEndSequence".equals( macros ) )
		{
			if( args.length > 0 )
			{
				String endSequence = (String)args[0];
				if( Utils.notEmptyString( endSequence ) )
				{
					session.setEndString( (String)args[0] );
					return "";
				}
			}

			session.setEndString( this.endSequence );
			return "";
		}
		else
		{
			return super.getValue( e, serv, connection, options, macros, args, globalArgs );
		}
	}
}
