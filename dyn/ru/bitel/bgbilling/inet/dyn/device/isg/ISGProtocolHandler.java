package ru.bitel.bgbilling.inet.dyn.device.isg;

import java.util.List;

import org.apache.log4j.Logger;

import ru.bitel.bgbilling.kernel.network.dhcp.DhcpOption;
import ru.bitel.bgbilling.kernel.network.dhcp.DhcpPacket;
import ru.bitel.bgbilling.kernel.network.radius.RadiusAttribute;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.modules.inet.access.sa.ProtocolHandlerAdapter;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.api.server.InetUtils;
import ru.bitel.bgbilling.modules.inet.api.server.bean.InetDeviceMap;
import ru.bitel.bgbilling.modules.inet.api.server.bean.InetDeviceMap.InetDeviceMapItem;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.sql.ConnectionSet;

/**
 * use {@link ru.bitel.bgbilling.modules.inet.dyn.device.cisco.ISGProtocolHandler} 
 */
@Deprecated
public class ISGProtocolHandler
    extends ProtocolHandlerAdapter
{
	private static final Logger log = Logger.getLogger( ISGProtocolHandler.class );
	
	private int portLength;
	
	@Override
    public void init( Setup setup, int moduleId, InetDevice inetDevice, InetDeviceType inetDeviceType, ParameterMap deviceConfig )
        throws Exception
    {
	    super.init( setup, moduleId, inetDevice, inetDeviceType, deviceConfig );
	    
	    InetDeviceMapItem device = InetDeviceMap.getInstance( moduleId ).get( inetDevice.getId() );;
	    
	    portLength = ISGUtils.getPortLength( device.getConfig() );
	    
	    log.info( "ISG port length: " + portLength );
    }

	@Override
    public void preprocessAccessRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
        throws Exception
    {
		// перенос последней части UserName в атрибут Calling-Station-Id (MAC адрес)
		String userName = request.getStringAttribute( -1, 1, null );
		if( userName != null )
		{
			 // перенос последней части UserName в атрибут Calling-Station-Id (MAC адрес)
            int pos = userName.lastIndexOf( ':' );
            if( pos > 0 )
            {
                request.setStringAttribute( -1, 31, userName.substring( pos + 1 ) );
                request.setStringAttribute( -1, 1, userName = userName.substring( 0, pos ) );
            }        

            if( portLength < ISGUtils.MAX_PORT_LENGTH )
            {
	            // урезание circuitId (порта) до последних x символов
	            pos = userName.lastIndexOf( ':' );
	            if( pos > 0 )
	            {
	                request.setStringAttribute( -1, 1, userName.substring( 0, pos + 1 ) + userName.substring( userName.length() - portLength * 2 ) );
	            }
            }
		}
    }
	
	private final static String prefix = "parent-session-id=";
	private final static int prefixLength = prefix.length();
	
	private final static String serviceInfoPrefix = "N";
	private final static int serviceInfoPrefixLength = serviceInfoPrefix.length();

	@Override
    public void preprocessAccountingRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
        throws Exception
    {
		preprocessAccessRequest( request, response, connectionSet );

		// перенос parent-session-id в Acct-Session-ID
		List<RadiusAttribute<?>> attributes = request.getAttributes( 9, 1 );
		for( RadiusAttribute<?> attr : attributes )
		{
			@SuppressWarnings("unchecked")
            String value = ((RadiusAttribute<String>)attr).getValue();
			if( value.startsWith( prefix ) )
			{
				request.setStringAttribute( -1, 44, value.substring( prefixLength ) );
				break;
			}
		}
		
		// перенос трафиков из Acct-Input-Octets в атрибуты с префиксом
		String serviceInfo = request.getStringAttribute( 9, 251, null );
		if( serviceInfo != null && serviceInfo.startsWith( serviceInfoPrefix )  )
		{
			String serviceName = serviceInfo.substring( serviceInfoPrefixLength );
			
			long output = InetUtils.getOutputOctets( request );
			long input = InetUtils.getInputOctets( request );
			request.addAttribute( new RadiusAttribute.RadiusAttributeString( 9,1, serviceName + "_IN:" + output ) );
			request.addAttribute( new RadiusAttribute.RadiusAttributeString( 9,1, serviceName + "_OUT:" + input ) );
		}
    }
	
	@Override
    public void preprocessDhcpRequest( DhcpPacket request, DhcpPacket response )
        throws Exception
    {
		DhcpOption option = request.getSubOption( (byte)1 );
		if( option != null )
		{
			byte[] currentValue = option.value;
			// circuitId вида 0004000e000a 
			if( currentValue.length == 6 )
			{
				byte[] value = new byte[portLength];
				// оставляем только порт, при этом длина зарезается но для разобранного пакета это уже не критично
				System.arraycopy( currentValue, 6 - portLength, value, 0, portLength);
				request.setSubOption( (byte)1, value);
			}
		}
    }
}