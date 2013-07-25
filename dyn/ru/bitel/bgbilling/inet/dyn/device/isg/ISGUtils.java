package ru.bitel.bgbilling.inet.dyn.device.isg;

import ru.bitel.common.ParameterMap;
import ru.bitel.common.Utils;

/**
 * use {@link ru.bitel.bgbilling.modules.inet.dyn.device.cisco.ISGUtils}
 */
@Deprecated
public class ISGUtils
{
	public static final int MAX_PORT_LENGTH = 4;
	
	/**
	 * Извлекает из конфигурации типа сервиса информацию о длине записи порта.
	 * @param deviceConfig
	 * @return
	 */
	public static final int getPortLength( ParameterMap deviceConfig )
	{
		int result = Utils.parseInt( deviceConfig.get( "isg.port.length", "" ), MAX_PORT_LENGTH );
		if( result > MAX_PORT_LENGTH || result < 1 )
		{
			result = MAX_PORT_LENGTH;
		}
		return result;
	}
}
