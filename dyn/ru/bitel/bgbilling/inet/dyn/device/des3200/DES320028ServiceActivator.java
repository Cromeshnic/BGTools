package ru.bitel.bgbilling.inet.dyn.device.des3200;

import java.util.*;
import java.util.concurrent.*;
import uk.co.westhawk.snmp.stack.*;
import org.apache.log4j.*;
import ru.bitel.common.*;
import ru.bitel.common.concurrent.*;
import ru.bitel.bgbilling.modules.inet.api.common.bean.*;
import ru.bitel.bgbilling.modules.inet.access.sa.*;

public class DES320028ServiceActivator
        extends ServiceActivatorAdapter
{
	private static final Logger logger = Logger.getLogger( DES320028ServiceActivator.class );

	InetDevice device;
	String host;
	int snmpVersion;
	int snmpPort;

	SnmpContext context;

	long[] oidRxValue = new AsnObjectId( "1.3.6.1.4.1.171.11.113.1.3.2.3.1.1.2" ).getOid();
	long[] oidTxValue = new AsnObjectId( "1.3.6.1.4.1.171.11.113.1.3.2.3.1.1.3" ).getOid();

	@Override
	public Object init( int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceConfig )
	{
		logger.info( "INIT" );

		this.device = device;

		this.host = device.getHost();
		this.snmpVersion = deviceConfig.getInt( "snmp.version", 1 );
		this.snmpPort = deviceConfig.getInt( "snmp.port", 161 );

		this.context = null;

		return true;
	}

	@Override
	public Object destroy()
	{
		logger.info( "DESTROY" );

		return true;
	}

	@Override
	public Object connect()
	        throws Exception
	{
		logger.info( "CONNECT" );

		switch( snmpVersion )
		{
			case 1:
			{
				this.context = new SnmpContext( host, snmpPort );
				break;
			}

			case 2:
			{
				this.context = new SnmpContextv2c( host, snmpPort );
				break;
			}

			default:
			{
				logger.info( "snmpVersion=" + snmpVersion );
				return false;
			}
		}

		this.context.setCommunity( device.getSecret() );

		return true;
	}

	@Override
	public Object disconnect()
	{
		logger.info( "DISCONNECT" );
		if( context != null )
		{
			context.destroy();
		}

		return true;
	}

	/**
	* Set speed on specified port in kbps.
	*/
	Future<?> setSpeed( int port, int rxKbps, int txKbps )
	        throws Exception
	{
		logger.info( "SET SPEED rx/tx: " + rxKbps + "/" + txKbps + " kbps" );

		AsnObjectId oidRx = new AsnObjectId( oidRxValue );
		oidRx.add( port );
		AsnObjectId oidTx = new AsnObjectId( oidTxValue );
		oidTx.add( port );

		SetPdu setPdu = new SetPdu( context );
		setPdu.addOid( oidRx, new AsnInteger( rxKbps ) );
		setPdu.addOid( oidTx, new AsnInteger( txKbps ) );

		FutureObserver<?> result = new FutureObserver<Object>()
		{
			@Override
			protected Object updateImpl( Observable obs, Object varbind )
			{
				SetPdu pdu = (SetPdu)obs;
				if( pdu.getErrorStatus() == AsnObject.SNMP_ERR_NOERROR )
				{
					logger.info( "Ok: " + pdu );

					return true;
				}
				else
				{
					logger.error( "PDU set error: " + varbind );
				}

				return false;
			}
		};

		setPdu.addObserver( result );
		setPdu.send();

		return result;
	}

	@Override
	public Future<?> serviceModify( ServiceActivatorEvent e )
	        throws Exception
	{
		logger.info( "SERVICE MODIFY" );

		int port = e.getNewInetServ().getInterfaceId();
		Set<Integer> optionSet = e.getNewOptions();
		int speed = 64;

		if( e.getNewState() == InetServ.STATE_ENABLE )
		{
			speed = 512;

			if( optionSet.contains( 1 ) )
			{
				speed = 1024;
			}
			else if( optionSet.contains( 2 ) )
			{
				speed = 2048;
			}
		}

		return setSpeed( port, speed, speed );
	}

	@Override
	public Object serviceCancel( ServiceActivatorEvent e )
	{
		logger.info( "SERVICE CANCEL" );
		return null;
	}
}
