package ru.bitel.bgbilling.inet.dyn.device.redback;

import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.modules.inet.access.sa.ProtocolHandlerAdapter;
import ru.bitel.common.Utils;
import ru.bitel.common.sql.ConnectionSet;

@Deprecated
public class SmartEdgeProtocolHandler
	extends ProtocolHandlerAdapter
{
	@Override
    public void preprocessAccessRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
        throws Exception
    {
		String macAddr = request.getStringAttribute( 2352, 145, null );
		byte[] remoteId = request.getByteAttribute( 2352, 96, null );
		byte[] circuitId = request.getByteAttribute( 2352, 97, null );

		if( macAddr != null && remoteId != null && circuitId != null )
		{
			String callingStation = macAddr.replaceAll( "\\-", "" );
			
			String userName = Utils.bytesToHexString( remoteId ) + ":" + Utils.bytesToHexString( circuitId );
			userName = userName.toLowerCase();

			request.setStringAttribute( -1, 1, userName );
			request.setStringAttribute( -1, 31, callingStation );
		}
    }
	
	@Override
    public void postprocessAccessRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
        throws Exception
    {
		response.removeAttributes( -1, 8 );		
    }


	@Override
    public void preprocessAccountingRequest( RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet )
        throws Exception
    {
		int acctStatusType = request.getIntAttribute( -1, 40, 0 );
		// старты получается не обрабатываем, сессия стартует по апдейту
		if( acctStatusType != 1 )
		{
			preprocessAccessRequest( request, response, connectionSet );

			Integer ipaddr = request.getIntAttribute( 2352, 132, null );
			if( ipaddr != null )
			{	
				request.setIntAttribute( -1, 8, ipaddr );	
			}
		}
    }
}