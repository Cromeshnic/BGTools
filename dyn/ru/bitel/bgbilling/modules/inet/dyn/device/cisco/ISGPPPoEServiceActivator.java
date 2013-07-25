package ru.bitel.bgbilling.modules.inet.dyn.device.cisco;

import ru.bitel.bgbilling.modules.inet.access.sa.ServiceActivator;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;

public class ISGPPPoEServiceActivator
    extends ISGServiceActivator
    implements ServiceActivator
{
	public ISGPPPoEServiceActivator()
	{
		super();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Object init( Setup setup, int moduleId, InetDevice device, InetDeviceType deviceType, ParameterMap deviceConfig )
	    throws Exception
	{
		super.init( setup, moduleId, device, deviceType, deviceConfig );

		// по умолчанию закрытие - PoD
		this.closeMode = deviceConfig.getInt( "sa.radius.connection.close.mode", CLOSE_MODE_POD );
		// по умолчанию, при переключении состояния из отлючен во включен и withoutBreak=false делаем тоже самое, что и при обычном закрытии сессии
		this.closeEnableMode = deviceConfig.getInt( "sa.radius.connection.close.enableMode", this.closeMode );

		// никакой работы с вторичной авторизацией (InetDhcpHelperProcessor/InetRadiusHelperProcessor) нет
		this.closeRemoveFromKeyMap = deviceConfig.getInt( "sa.radius.connection.close.removeFromKeyMap", 0 ) > 0;

		// при закрытии соединения не отправляем дополнительно запросы на отключение сервисов
		this.disableServicesOnClose = deviceConfig.getInt( "sa.radius.connection.close.disableServices", 0 ) > 0;

		return null;
	}
}
