package ru.bitel.bgbilling.inet.dyn.device.isg;

import ru.bitel.bgbilling.kernel.script.server.dev.EventScript;
import ru.bitel.bgbilling.kernel.script.server.dev.EventScriptBase;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetServ;
import ru.bitel.bgbilling.modules.inet.api.server.bean.InetDeviceMap;
import ru.bitel.bgbilling.modules.inet.api.server.bean.InetDeviceMap.InetDeviceMapItem;
import ru.bitel.bgbilling.modules.inet.api.server.event.InetServChangingEvent;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.sql.ConnectionSet;

/**
 * use {@link ru.bitel.bgbilling.modules.inet.dyn.device.cisco.ISGLoginGenerator}
 */
@Deprecated
public class ISGLoginGenerator
	extends EventScriptBase<InetServChangingEvent>
	implements EventScript<InetServChangingEvent> 
{
	@Override
	public void onEvent( InetServChangingEvent e, Setup setup, ConnectionSet connectionSet )
		throws Exception
	{
		InetServ inetServ = e.getInetServ();
		
		int deviceId = inetServ.getDeviceId();

		InetDeviceMapItem device = InetDeviceMap.getInstance( e.getModuleId() ).get( deviceId );
		
		int portLength = ISGUtils.getPortLength( device.getConfig() );
		
		int port = inetServ.getInterfaceId();
		String port_str = String.format( "%1$0" + (portLength * 2) + "x", port % (1<<(portLength * 8)) );

		String userName = "0006" + device.getDevice().getIdentifier().toLowerCase() + ":" + port_str;
		inetServ.setLogin( userName );
		inetServ.setPassword( "123" );

		print( "Setting userName:" + userName );
	}
}