package ru.dsi.bgbilling.modules.inet.dyn.device.cisco;

import org.apache.log4j.Logger;
import ru.bitel.bgbilling.common.BGException;
import ru.bitel.bgbilling.kernel.container.managed.ServerContext;
import ru.bitel.bgbilling.kernel.event.EventListener;
import ru.bitel.bgbilling.kernel.event.EventListenerContext;
import ru.bitel.bgbilling.kernel.event.EventProcessor;
import ru.bitel.bgbilling.kernel.network.radius.RadiusDictionary;
import ru.bitel.bgbilling.kernel.network.radius.RadiusPacket;
import ru.bitel.bgbilling.kernel.network.radius.RadiusProtocolHandler;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDevice;
import ru.bitel.bgbilling.modules.inet.api.common.bean.InetDeviceType;
import ru.bitel.bgbilling.modules.inet.radius.InetRadiusProcessor;
import ru.bitel.bgbilling.server.util.Setup;
import ru.bitel.common.ParameterMap;
import ru.bitel.common.sql.ConnectionSet;
import ru.bitel.common.worker.ThreadContext;
import ru.bitel.oss.systems.inventory.resource.common.DeviceInterfaceService;
import ru.bitel.oss.systems.inventory.resource.common.bean.DeviceInterface;
import ru.bitel.oss.systems.inventory.resource.common.event.DeviceInterfaceModifiedEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author cromeshnic@gmail.com
 * ProtocolHandler для работы с cisco ip subscriber interface + cisco ISG
 * В предобработке устанавливается опция пакета InetRadiusProcessor.INTERFACE_ID, где указывается номер интерфейса в биллинге,
 * соответствующий атрибуту Nas-Port-Id из пакета
 * Соответствие определяется по шаблонам, заданным в конфигурации, и имени интерфейса сервиса в биллинге
 * Предполагается, что клиент авторизуется именно на том устройстве, на котором указан этот ProtocolHandler (не на дочерних)
 *
 * Параметры конфигурации:
 * radius.ipoe.nas_port_id.pattern.[i].pattern - регэксп-шаблон для имени интерфейса в биллинге
 * radius.ipoe.nas_port_id.pattern.[i].replacement - выражение для построения nas_port_id по шаблону
 *
 * Пример:
 * Gi0/0.1112 -> 0/0/0/1112
 * Gi0/0.20150105 -> 0/0/0/105.2015
 *
 */
public class ISGIPoEProtocolHandler extends ISGProtocolHandler implements RadiusProtocolHandler {

    private static final Logger logger = Logger.getLogger( ISGIPoEProtocolHandler.class );

    /**
     * Кэш интерфейсов устройства
     */
    private volatile DeviceNasPortMap ifaceMap;

    @Override
    public void init(Setup setup, int moduleId, InetDevice inetDevice, InetDeviceType inetDeviceType, ParameterMap deviceConfig) throws Exception {
        super.init(setup, moduleId, inetDevice, inetDeviceType, deviceConfig);
        this.ifaceMap = new DeviceNasPortMap(moduleId, inetDevice.getId(), deviceConfig.subIndexed("radius.ipoe.nas_port_id.pattern."));
    }

    @Override
    public void preprocessAccountingRequest(RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet) throws Exception {
        super.preprocessAccountingRequest(request, response, connectionSet);
        //по Nas-Port-Id в пакете ищем номер порта на устройстве и указываем его в опции пакета
        setBGIfaceId( request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void preprocessAccessRequest(RadiusPacket request, RadiusPacket response, ConnectionSet connectionSet) throws Exception {
        super.preprocessAccessRequest(request, response, connectionSet);
        //по Nas-Port-Id в пакете ищем номер порта на устройстве и указываем его в опции пакета
        setBGIfaceId( request);
    }

    /**
     * по Nas-Port-Id в пакете ищем номер порта на устройстве и указываем его в опции пакета InetRadiusProcessor.INTERFACE_ID
     * @param request    радиус-пакет
     */
    private void setBGIfaceId(RadiusPacket request) {
        String nas_port_id = request.getStringAttribute(-1, RadiusDictionary.NAS_Port_Id, null);
        Integer port=-1;
        if(nas_port_id!=null){
            port = this.ifaceMap.getIfacePort(nas_port_id);
        }
        if(null==port)
        {
            port=-1;//Насчёт port=0 и port=-1 - см http://forum.bgbilling.ru/viewtopic.php?f=44&t=7694&p=64541#p64541
        }
        request.setOption(InetRadiusProcessor.INTERFACE_ID, port);
    }

    /**
     * Кэш соответствий Nas-Port-Id -> id интерфейса в биллинге для устройства
     * Обновляется при изменении порта или перезагрузке конфигурации
     */
    private class DeviceNasPortMap implements EventListener<DeviceInterfaceModifiedEvent> {
        /**
         * Соответствие cisco Nas-Port-Id -> id интерфейса в биллинге
         */
        private volatile Map<String, Integer> nasPortIdToBGPortIdMap;
        private final int moduleId;
        private final int deviceId;
        /**
         * Список шаблонов-регулярных выражений, по которым будем получать Nas-Port-Id по названию интерфейса
         * Список паттернов не обновляется, т.к. берётся из конфига.
         * При перезагрузке конфига в любом случае ISGIPoEProtocolHandler будет переинициализирован целиком
         */
        private final SortedMap<Integer, ParameterMap> patternMap;

        public DeviceNasPortMap(int moduleId, int deviceId, SortedMap<Integer, ParameterMap> patternMap) throws BGException {
            this.moduleId = moduleId;
            this.deviceId = deviceId;
            this.patternMap = patternMap;
            EventProcessor.getInstance().addListener(this, DeviceInterfaceModifiedEvent.class);
            this.load();
        }

        private synchronized void load(){
            this.nasPortIdToBGPortIdMap = new HashMap<String, Integer>();
            //Перебираем порты устройств
            logger.info("(Re)loading DeviceNasPortMap for device "+this.deviceId);
            ServerContext ctx = (ServerContext) ThreadContext.get();
            try {
                DeviceInterfaceService devicePortService = ctx.getService(DeviceInterfaceService.class, moduleId);
                List<DeviceInterface> deviceIfaceList = devicePortService.devicePortList(this.deviceId);
                String nasPortId;
                if(deviceIfaceList!=null){
                    for(DeviceInterface iface : deviceIfaceList){
                        nasPortId = nasPortIdByIfaceTitle(iface.getTitle());
                        if(null!=nasPortId){
                            nasPortIdToBGPortIdMap.put(nasPortId, iface.getPort());
                            logger.debug("[device id=" + this.deviceId + "]: nas-port-id='" + nasPortId + "' -> " + iface.getPort());
                        }
                    }
                }
            } catch (BGException e) {
                logger.error("Error (re)loading DeviceNasPortMap", e);
            }
        }

        /**
         * Возвращает Nas-Port-Id по имени интерфейса на основе регекспов из patternMap
         * @param ifaceTitle имя инерфейса (ex Gi0/0.123)
         * @return Nas-Port-Id (ex 0/0/0/123)
         */
        protected String nasPortIdByIfaceTitle(String ifaceTitle){
            if(null==ifaceTitle){
                return null;
            }
            Pattern p;
            Matcher m;
            String pattern;
            String replacement;
            String nasPortId;
            for(Map.Entry<Integer, ParameterMap> patternMapEntry : patternMap.entrySet()){
                pattern = patternMapEntry.getValue().get("pattern", null);
                replacement = patternMapEntry.getValue().get("replacement", null);
                if(pattern!=null && replacement!=null){
                    p = Pattern.compile(pattern);
                    m = p.matcher(ifaceTitle);
                    if (m.find()) {
                        //Получаем логин путём подстановки найденных capturing groups в $1, $2 и т.д. шаблона
                        nasPortId = m.replaceFirst(replacement);
                        return nasPortId;
                    }
                }
            }
            return null;
        }

        /**
         * Получаем id порта в биллинге по nas_port_id из кэша
         */
        public Integer getIfacePort(String nas_port_id) {
            return this.nasPortIdToBGPortIdMap.get(nas_port_id);
        }

        /**
         * Обновляем кэш при изменении интерфейса
         * @throws ru.bitel.bgbilling.common.BGException
         */
        @Override
        public void notify(DeviceInterfaceModifiedEvent event, EventListenerContext eventListenerContext) throws BGException {
            DeviceInterface deviceIface = event.getNewItem();
            if(deviceIface==null){
                deviceIface = event.getOldItem();
            }
            if(deviceIface!=null){
                if(deviceIface.getDeviceId()==this.deviceId){
                    this.load();
                }
            }
        }
    }
}