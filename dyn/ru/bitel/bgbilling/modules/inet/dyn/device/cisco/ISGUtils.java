package ru.bitel.bgbilling.modules.inet.dyn.device.cisco;

import ru.bitel.common.ParameterMap;

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
		int result = deviceConfig.getInt( "isg.port.length", MAX_PORT_LENGTH );
		if( result > MAX_PORT_LENGTH || result < 1 )
		{
			result = MAX_PORT_LENGTH;
		}

		return result;
	}
}
