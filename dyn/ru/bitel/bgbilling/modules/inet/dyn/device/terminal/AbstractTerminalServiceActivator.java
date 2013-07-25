package ru.bitel.bgbilling.modules.inet.dyn.device.terminal;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorAdapter;
import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivatorEvent;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetConnection;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.runtime.InetInterfaceMap;
import ru.bitel.bgbilling.modules.inet.runtime.InetOptionRuntime;
import ru.bitel.bgbilling.modules.inet.runtime.InetOptionRuntimeMap;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;
import ru.bitel.common.inet.IpAddress;
import ru.bitel.common.inet.IpNet;
import ru.bitel.common.util.MacrosFormat;
import bitel.billing.common.IPUtils;

/**
 * Обработчик активации сервисов на основе выполнения простых команд.
 * @author amir
 *
 */
public abstract class AbstractTerminalServiceActivator
    extends ServiceActivatorAdapter
    implements ServiceActivator
{
	private static final Logger logger = Logger.getLogger( AbstractTerminalServiceActivator.class );
	
	protected int moduleId;

	/**
	 * Код устройства.
	 */
	protected int deviceId;

	/**
	 * Устройство.
	 */
	protected InetDevice device;

	/**
	 * Конфигурация устройства.
	 */
	protected ParameterMap config;

	protected String host;
	protected int port;

	protected String username;
	protected String password;

	protected int timeout;

	/**
	 * Мап опций Inet.
	 */
	protected InetOptionRuntimeMap optionRuntimeMap;

	protected MacrosFormat macrosFormat;

	protected static class CommandSet
	{
		public final String[] enableCommands;
		public final String[] disableCommands;

		public CommandSet( String[] enableCommands, String[] disableCommands )
		{
			this.enableCommands = enableCommands;
			this.disableCommands = disableCommands;
		}
	}

	/**
	 * Команды, выполняемые при подключении к терминалу.
	 */
	protected String[] connectCommands;
	/**
	 * Команды, выполняемые перед отключением от терминала.
	 */
	protected String[] disconnectCommands;

	/**
	 * Команда выхода (отключечния от терминала).
	 */
	protected String exitCommand;

	/**
	 * Команды создания сервиса на устройстве.
	 */
	protected String[] servCreateCommands;
	
	/**
	 * Команды удаления сервиса с устройства.
	 */
	protected String[] servCancelCommands;

	/**
	 * Команды изменения сервиса на устройстве.
	 */
	protected CommandSet servModifyCommands;
	/**
	 * Команды изменения опций сервиса на устройстве.
	 */
	protected Map<Integer, CommandSet> servOptionModifyCommandsMap;
	
	/**
	 * Команды изменения соединения на устройстве.
	 */
	protected CommandSet connectionModifyCommands;
	/**
	 * Команды изменения опций соединения на устройстве.
	 */
	protected Map<Integer, CommandSet> connectionOptionModifyCommandsMap;
	/**
	 * Команды закрытия соединения на устройстве.
	 */
	protected String[] connectionCloseCommands;
	
	/**
	 * Команды на начало аккаунтинга (старта соединения).
	 */
	protected String[] onAccountingStartCommands;
	/**
	 * Команды на окончание аккаунтинга (стоп соединения).
	 */
	protected String[] onAccountingStopCommands;

	/**
	 * Фильтр опций Inet, с котороми происходит работа.
	 */
	protected Set<Integer> workingOptions;
	
	/**
	 * Нужно ли после смены состояния соединения сразу менять состояние в базе.
	 */
	protected boolean needConnectionStateModify;

	@Override
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap config )
	    throws Exception
	{
		super.init( setup, moduleId, device, deviceType, config );
		
		this.moduleId = moduleId;

		this.deviceId = device.getId();
		this.device = device;
		this.config = config;

		List<InetSocketAddress> hosts = device.getHosts();
		if( hosts.size() > 0 )
		{
			this.host = hosts.get( 0 ).getHostName();
			this.port = hosts.get( 0 ).getPort();
		}

		this.host = config.get( "sa.host", this.host );
		this.port = config.getInt( "sa.port", this.port );

		this.username = device.getUsername();
		this.password = device.getPassword();

		this.optionRuntimeMap = InetOptionRuntimeMap.getInstance( moduleId );

		this.macrosFormat = createMacrosFormat();

		this.timeout = this.config.getInt( "sa.command.timeout", 60000 );

		this.exitCommand = this.config.get( "sa.command.exit", "exit" );

		this.connectCommands = parseCommands( this.config, "sa.command.connect", null );
		this.disconnectCommands = parseCommands( this.config, "sa.command.disconnect", null );

		String[] servEnableCommands = parseCommands( this.config, "sa.command.serv.enable", null );
		String[] servDisableCommands = parseCommands( this.config, "sa.command.serv.disable", null );

		this.servCreateCommands = parseCommands( this.config, "sa.command.serv.create", servEnableCommands );
		this.servCancelCommands = parseCommands( this.config, "sa.command.serv.cancel", servDisableCommands );

		this.servModifyCommands = new CommandSet( servEnableCommands, servDisableCommands );
		this.servOptionModifyCommandsMap = parseOptionCommands( config, "sa.command.inetOption." );
		this.servOptionModifyCommandsMap.putAll( parseOptionCommands( config, "sa.command.serv.inetOption." ) );

		String[] connectionEnableCommands = parseCommands( this.config, "sa.command.connection.enable", null );
		String[] connectionDisableCommands = parseCommands( this.config, "sa.command.connection.disable", null );

		this.connectionModifyCommands = new CommandSet( connectionEnableCommands, connectionDisableCommands );
		this.connectionOptionModifyCommandsMap = parseOptionCommands( config, "sa.command.connection.inetOption." );

		this.connectionCloseCommands = parseCommands( this.config, "sa.command.connection.close", null );

		this.onAccountingStartCommands = parseCommands( this.config, "sa.command.onAccountingStart", null );
		this.onAccountingStopCommands = parseCommands( this.config, "sa.command.onAccountingStop", null );

		Set<Integer> rootOptions = Utils.toIntegerSet( config.get( "sa.inetOption.root", null ) );
		this.workingOptions = new HashSet<Integer>();

		for( Integer rootOption : rootOptions )
		{
			InetOptionRuntime rootOptionRuntime = InetOptionRuntimeMap.getInstance( moduleId ).get( rootOption );
			if( rootOptionRuntime != null )
			{
				this.workingOptions.addAll( rootOptionRuntime.descendantIds );
			}
		}

		workingOptions.remove( 0 );

		if( this.workingOptions.size() == 0 )
		{
			this.workingOptions = null;
		}

		needConnectionStateModify = config.getInt( "sa.command.connection.stateModify", 0 ) > 0;

		return null;
	}

	protected static final Pattern semicolonPattern = Pattern.compile( "\\s*;\\s*" );

	protected String[] parseCommands( ParameterMap config, String prefix, String[] def )
	{
		List<String> list = new ArrayList<String>();

		String param = config.get( prefix );
		// команды заведены через точку с запятой
		if( Utils.notBlankString( param ) )
		{
			list.addAll( Arrays.asList( semicolonPattern.split( param ) ) );
		}
		// команды заведены отдельными параметрами 	
		else
		{
			for( ParameterMap params : config.subIndexed( prefix + "." ).values() )
			{
				String command = params.get( "", null );
				if( Utils.notBlankString( command ) )
				{
					list.add( command );
				}
			}
		}

		logger.debug( prefix + " commands: " + list );

		if( list.size() == 0 )
		{
			return def;
		}

		return list.toArray( new String[list.size()] );
	}
	
	protected Map<Integer, CommandSet> parseOptionCommands( ParameterMap config, String prefix )
	{
		Map<Integer, CommandSet> result = new HashMap<Integer, CommandSet>();

		for( Map.Entry<Integer, ParameterMap> e : config.subIndexed( prefix ).entrySet() )
		{
			final Integer option = e.getKey();
			final ParameterMap params = e.getValue();

			String[] enableCommands = parseCommands( params, "enable", null );
			String[] disableCommands = parseCommands( params, "disable", null );

			if( enableCommands != null || disableCommands != null )
			{
				result.put( option, new CommandSet( enableCommands, disableCommands ) );
			}
		}

		return result;
	}

	protected MacrosFormat createMacrosFormat()
	{
		return new MacrosFormat()
		{
			@SuppressWarnings("unchecked")
			@Override
			protected Object invoke( String macros, Object[] args, Object[] globalArgs )
			{
				if( "arg".equals( macros ) )
				{
					return globalArgs[getInt( args, 0, 0 )];
				}
				else if( "param".equals( macros ) )
				{
					if( args.length > 2 )
					{
						Object o = args[0];
						if( o instanceof InetOptionRuntime )
						{
							return ((InetOptionRuntime)o).inheritedConfig.get( getString( args, 1, "param" ), getString( args, 2, "" ) );
						}
						else
						{
							return getString( args, 2, "" );
						}
					}
					else
					{
						return AbstractTerminalServiceActivator.this.config.get( getString( args, 0, "param" ), getString( args, 1, "" ) );
					}
				}
				else if( "host".equals( macros ) )
				{
					List<InetSocketAddress> hostList = AbstractTerminalServiceActivator.this.device.getHosts();

					if( hostList.size() > 0 )
					{
						return hostList.get( 0 ).getHostName();
					}
					else
					{
						return null;
					}
				}
				else if( "concat".equals( macros ) )
				{
					StringBuilder result = new StringBuilder();
					for( Object arg : args )
					{
						result.append( arg );
					}

					return result.toString();
				}
				else if( "option".equals( macros ) )
				{
					Set<Integer> options = (Set<Integer>)globalArgs[3];

					if( args.length > 0 )
					{
						int rootOption = getInt( args, 0, 0 );
						if( rootOption > 0 )
						{
							InetOptionRuntime rootOptionRuntime = optionRuntimeMap.get( rootOption );
							for( Integer option : options )
							{
								if( rootOptionRuntime.descendantIds.contains( option ) )
								{
									return optionRuntimeMap.get( option );
								}
							}

							return null;
						}
					}

					for( Integer option : options )
					{
						return optionRuntimeMap.get( option );
					}

					return null;
				}
				else if( "switch".equals( macros ) )
				{
					int value = getInt( args, 0, 0 );
					boolean def = args.length % 2 == 0;
					int max = def ? args.length - 1 : args.length;

					for( int i = 1; i < max; i = i + 2 )
					{
						if( value == getInt( args, i, 0 ) )
						{
							return args[i + 1];
						}
					}

					// default
					if( args.length % 2 == 0 )
					{
						return args[args.length - 1];
					}

					return null;
				}
				else
				{
					try
					{
						Object result ;
						
						Object arg = globalArgs[2];
						if(arg instanceof InetConnection)
						{
							result = getValue( (ServiceActivatorEvent)globalArgs[0], (InetServ)globalArgs[1], (InetConnection)globalArgs[2], (Set<Integer>)globalArgs[3], macros, args, globalArgs );
						}
						else
						{
							result = getValue( (ServiceActivatorEvent)globalArgs[0], (InetServ)globalArgs[1], (InetConnection)globalArgs[2], (Set<Integer>)globalArgs[3], macros, args, globalArgs );
						}

						if( result != null )
						{
							return result;
						}
					}
					catch( Exception e )
					{
						logger.error( e.getMessage(), e );
					}

					return "$" + macros;
				}
			}
		};
	}

	protected Object getValue( final ServiceActivatorEvent e, final InetServ serv, final InetConnection connection, final Set<Integer> options, final String macros, final Object[] args, final Object[] globalArgs )
	    throws Exception
	{
		if( "ip".equals( macros ) )
		{
			if( connection != null && connection.getInetAddressBytes() != null )
			{
				return IpAddress.toString( connection.getInetAddressBytes() );
			}
			else
			{
				return IpAddress.toString( serv.getAddressFrom() );
			}
		}
		else if( "net".equals( macros ) )
		{
			return IpNet.toString( serv.getAddressFrom(), serv.getAddressTo() );
		}
		else if( "mask".equals( macros ) || "bitmask".equals( macros ))
		{
			return IpNet.getMask( serv.getAddressFrom(), serv.getAddressTo() );
		}			
		else if( "netmask".equals( macros )  )
		{
			int bitMask = IpNet.getMask( serv.getAddressFrom(), serv.getAddressTo() );
			long mask = (0xFFFFFFFFl << (32 - bitMask)) & 0xFFFFFFFFl;
			
			return IPUtils.convertLongIpToString( mask );
		}
		else if( "netmaskWild".equals( macros ) )
		{
			int bitMask = IpNet.getMask( serv.getAddressFrom(), serv.getAddressTo() );
			long mask = (0xFFFFFFFFl << (32 - bitMask)) & 0xFFFFFFFFl;
			long maskWild = mask ^ 0xFFFFFFFFL;

			return IPUtils.convertLongIpToString( maskWild );
		}
		else if( "vlan".equals( macros ) )
		{
			return serv.getVlan();
		}
		else if( "iface".equals( macros ) || "port".equals( macros ) )
		{
			return serv.getInterfaceId();
		}
		else if( "ifaceTitle".equals( macros ) )
		{
			final int interfaceId = serv.getInterfaceId();
			return InetInterfaceMap.getInstance( moduleId ).getInterfaceTitle( deviceId, interfaceId );
		}
		else if( "mac".equals( macros ) )
		{
			return InetServ.macAddressToString( serv.getMacAddressListBytes() );
		}
		else if( "macBytes".equals( macros ) )
		{
			return Utils.bytesToString( serv.getMacAddressListBytes(), false, null );
		}
		else if( "servTitle".equals( macros ) )
		{
			return serv.getTitle();
		}
		else if( "contractId".equals( macros ) )
		{
			return serv.getContractId();
		}
		else if( "servId".equals( macros ) )
		{
			return serv.getId();
		}

		return null;
	}

	protected Object executeCommands( ServiceActivatorEvent e, InetServ serv, InetConnection connection, Set<Integer> options, String[] commands )
	    throws Exception
	{
		if( commands == null )
		{
			return null;
		}

		if( this.workingOptions != null )
		{
			options = new HashSet<Integer>( options );
			options.retainAll( this.workingOptions );
		}

		for( String command : commands )
		{
			command = this.macrosFormat.format( command, e, serv, connection, options );
			if( Utils.notBlankString( command ) )
			{
				executeCommand( command.trim() );
			}
		}

		return null;
	}

	protected abstract void executeCommand( String command )
	    throws Exception;

	@Override
	public Object connect()
	    throws Exception
	{
		if( this.connectCommands != null )
		{
			for( String command : this.connectCommands )
			{
				executeCommand( command );
			}
		}

		return null;
	}

	@Override
	public Object disconnect()
	    throws Exception
	{
		if( this.disconnectCommands != null )
		{
			for( String command : this.disconnectCommands )
			{
				executeCommand( command );
			}
		}

		return null;
	}

	@Override
	public Object serviceCreate( ServiceActivatorEvent e )
	    throws Exception
	{
		return executeCommands( e, e.getNewInetServ(), null, e.getNewOptions(), this.servCreateCommands );
	}

	@Override
	public Object serviceCancel( ServiceActivatorEvent e )
	    throws Exception
	{
		return executeCommands( e, e.getOldInetServ(), null, e.getOldOptions(), this.servCancelCommands );
	}

	@Override
	public Object serviceModify( ServiceActivatorEvent e )
	    throws Exception
	{
		// отключение
		if( e.getNewState() == InetServ.STATE_DISABLE )
		{
			return serviceDisable( e );
		}

		// включение
		if( e.getOldState() == InetServ.STATE_DISABLE )
		{
			return serviceEnable( e );
		}

		// изменение опций
		return serviceOptionsModify( e );
	}

	protected Object serviceDisable( ServiceActivatorEvent e )
	    throws Exception
	{
		switchOptions( e, e.getOldInetServ(), null, this.servOptionModifyCommandsMap, e.getOldOptions(), null );

		executeCommands( e, e.getOldInetServ(), null, e.getOldOptions(), this.servModifyCommands.disableCommands );

		return null;
	}

	protected Object serviceEnable( ServiceActivatorEvent e )
	    throws Exception
	{
		executeCommands( e, e.getNewInetServ(), null, e.getNewOptions(), this.servModifyCommands.enableCommands );

		switchOptions( e, e.getNewInetServ(), null, this.servOptionModifyCommandsMap, null, e.getNewOptions() );

		return null;
	}

	protected Object serviceOptionsModify( ServiceActivatorEvent e )
	    throws Exception
	{
		Set<Integer> removeOptions = e.getOptionsToRemove();
		Set<Integer> addOptions = e.getOptionsToAdd();

		switchOptions( e, e.getNewInetServ(), null, this.servOptionModifyCommandsMap, removeOptions, addOptions );

		return null;
	}

	@Override
	public Object connectionModify( ServiceActivatorEvent e )
	    throws Exception
	{
		// отключение
		if( e.getNewState() == InetServ.STATE_DISABLE )
		{
			// устанавливаем флаг, что нужно будет поменять состояние соединения в базе
			if( needConnectionStateModify )
			{
				e.setConnectionStateModified( true );
			}
			
			return connectionDisable( e );
		}

		// включение
		if( e.getOldState() == InetServ.STATE_DISABLE )
		{
			// устанавливаем флаг, что нужно будет поменять состояние соединения в базе
			if( needConnectionStateModify )
			{
				e.setConnectionStateModified( true );
			}
			
			return connectionEnable( e );
		}

		// измененний опций
		return connectionOptionsModify( e );
	}

	protected Object connectionDisable( ServiceActivatorEvent e )
	    throws Exception
	{
		switchOptions( e, e.getNewInetServ(), e.getConnection(), this.connectionOptionModifyCommandsMap, e.getOldOptions(), null );

		executeCommands( e, e.getNewInetServ(), e.getConnection(), e.getOldOptions(), this.connectionModifyCommands.disableCommands );

		return null;
	}

	protected Object connectionEnable( ServiceActivatorEvent e )
	    throws Exception
	{
		executeCommands( e, e.getNewInetServ(), e.getConnection(), e.getNewOptions(), this.connectionModifyCommands.enableCommands );

		switchOptions( e, e.getNewInetServ(), e.getConnection(), this.connectionOptionModifyCommandsMap, null, e.getNewOptions() );

		return null;
	}

	protected Object connectionOptionsModify( ServiceActivatorEvent e )
	    throws Exception
	{
		Set<Integer> removeOptions = e.getOptionsToRemove();
		Set<Integer> addOptions = e.getOptionsToAdd();

		switchOptions( e, e.getNewInetServ(), e.getConnection(), this.connectionOptionModifyCommandsMap, removeOptions, addOptions );

		return null;
	}

	protected void switchOptions( ServiceActivatorEvent e, InetServ serv, InetConnection connection, Map<Integer, CommandSet> optionModifyCommandsMap,
	                              Set<Integer> optionsDisable, Set<Integer> optionsEnable )
	    throws Exception
	{
		if( optionsDisable != null )
		{
			if( this.workingOptions != null )
			{
				optionsDisable = new HashSet<Integer>( optionsDisable );
				optionsDisable.retainAll( this.workingOptions );
			}

			for( Integer option : optionsDisable )
			{
				CommandSet commandSet = optionModifyCommandsMap.get( option );
				if( commandSet != null && commandSet.disableCommands != null )
				{
					executeCommands( e, serv, connection, Collections.singleton( option ), commandSet.disableCommands );
				}
			}
		}

		if( optionsEnable != null )
		{
			if( this.workingOptions != null )
			{
				optionsEnable = new HashSet<Integer>( optionsEnable );
				optionsEnable.retainAll( this.workingOptions );
			}

			for( Integer option : optionsEnable )
			{
				CommandSet commandSet = optionModifyCommandsMap.get( option );
				if( commandSet != null && commandSet.enableCommands != null )
				{
					executeCommands( e, serv, connection, Collections.singleton( option ), commandSet.enableCommands );
				}
			}
		}
	}

	@Override
	public Object connectionClose( ServiceActivatorEvent e )
	    throws Exception
	{
		executeCommands( e, e.getNewInetServ(), e.getConnection(), e.getNewOptions(), this.connectionCloseCommands );

		return null;
	}

	@Override
	public Object onAccountingStart( ServiceActivatorEvent e )
	    throws Exception
	{
		executeCommands( e, e.getNewInetServ(), e.getConnection(), e.getNewOptions(), this.onAccountingStartCommands );

		return null;
	}

	@Override
	public Object onAccountingStop( ServiceActivatorEvent e )
	    throws Exception
	{
		executeCommands( e, e.getNewInetServ(), e.getConnection(), e.getNewOptions(), this.onAccountingStopCommands );

		return null;
	}
}
