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
import bitel.billing.server.util.telnet.TelnetSession;

/**
 *  {@inheritDoc}
 *  @see AbstractTerminalServiceActivator
 */
public class TelnetServiceActivator
    extends AbstractTerminalServiceActivator
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( TelnetServiceActivator.class );

	protected String endSequence;

	protected TelnetSession session;

	protected boolean lazyConnect;

	@Override
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap config )
	    throws Exception
	{
		super.init( setup, moduleId, device, deviceType, config );
		
		if( this.port <= 0 )
		{
			this.port = 23;
		}

		this.endSequence = config.get( "sa.endSequence", "#" );

		this.lazyConnect = config.getInt( "sa.lazyConnect", 0 ) > 0;

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
		if( lazyConnect )
		{
			return null;
		}

		return connectImpl();
	}

	protected Object connectImpl()
	    throws Exception
	{
		TelnetSession session = new TelnetSession( host, port );
		session.setTimeout( timeout );

		session.setEndString( ":" );

		session.connect();
		logger.info( "Connected" );
		
		this.session = session;

		logger.info( session.doCommand( username ) );
		logger.info( "Login entered" );

		if( Utils.notBlankString( endSequence ) )
		{
			session.setEndString( endSequence );
		}

		logger.info( session.doCommand( password ) );
		logger.info( "Password entered" );

		// logger.info( session.doCommand( "terminal length 0" ) );
		// logger.info( session.doCommand( "terminal width 0" ) );

		return super.connect();
	}

	protected TelnetSession getSession()
	    throws Exception
	{
		if( session != null )
		{
			return session;
		}

		connectImpl();

		return session;
	}

	@Override
	public Object disconnect()
	    throws Exception
	{
		if( session != null )
		{
			try
			{
				super.disconnect();

				session.doCommandAsync( this.exitCommand );
			}
			finally
			{
				session.disconnect();

				session = null;

				logger.debug( "Disconnected" );
			}
		}

		return null;
	}

	@Override
	protected void executeCommand( final String command )
	    throws Exception
	{
		final TelnetSession session = getSession();

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
					getSession().setEndString( (String)args[0] );
					return "";
				}
			}

			getSession().setEndString( this.endSequence );
			return "";
		}
		else
		{
			return super.getValue( e, serv, connection, options, macros, args, globalArgs );
		}
	}
}
