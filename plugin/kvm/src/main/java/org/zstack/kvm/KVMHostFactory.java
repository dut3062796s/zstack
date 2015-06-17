package org.zstack.kvm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriComponentsBuilder;
import org.zstack.compute.host.HostGlobalConfig;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.ansible.AnsibleFacade;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusSteppingCallback;
import org.zstack.core.cloudbus.ResourceDestinationMaker;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.config.GlobalConfig;
import org.zstack.core.config.GlobalConfigException;
import org.zstack.core.config.GlobalConfigUpdateExtensionPoint;
import org.zstack.core.config.GlobalConfigValidatorExtensionPoint;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.header.Component;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.*;
import org.zstack.header.managementnode.ManagementNodeChangeListener;
import org.zstack.header.message.MessageReply;
import org.zstack.header.message.NeedReplyMessage;
import org.zstack.header.network.l2.L2NetworkType;
import org.zstack.header.volume.MaxDataVolumeNumberExtensionPoint;
import org.zstack.header.volume.VolumeConstant;
import org.zstack.header.volume.VolumeFormat;
import org.zstack.utils.SizeUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.data.SizeUnit;
import org.zstack.utils.logging.CLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KVMHostFactory implements HypervisorFactory, Component, ManagementNodeChangeListener, MaxDataVolumeNumberExtensionPoint {
    private static final CLogger logger = Utils.getLogger(KVMHostFactory.class);

    public static final HypervisorType hypervisorType = new HypervisorType(KVMConstant.KVM_HYPERVISOR_TYPE);
    public static final VolumeFormat QCOW2_FORMAT = new VolumeFormat(VolumeConstant.VOLUME_FORMAT_QCOW2, hypervisorType);
    public static final VolumeFormat RAW_FORMAT = new VolumeFormat(VolumeConstant.VOLUME_FORMAT_RAW, hypervisorType);
    private List<KVMHostConnectExtensionPoint> connectExtensions = new ArrayList<KVMHostConnectExtensionPoint>();
    private Map<L2NetworkType, KVMCompleteNicInformationExtensionPoint> completeNicInfoExtensions = new HashMap<L2NetworkType, KVMCompleteNicInformationExtensionPoint>();
    private int maxDataVolumeNum;

    static {
        RAW_FORMAT.newFormatInputOutputMapping(hypervisorType, QCOW2_FORMAT.toString());
    }

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private PluginRegistry pluginRgty;
    @Autowired
    private AnsibleFacade asf;
    @Autowired
    private ResourceDestinationMaker destMaker;
    @Autowired
    private CloudBus bus;

    @Override
    public HostVO createHost(HostVO vo, APIAddHostMsg msg) {
        APIAddKVMHostMsg amsg = (APIAddKVMHostMsg) msg;
        KVMHostVO kvo = new KVMHostVO(vo);
        kvo.setUsername(amsg.getUsername());
        kvo.setPassword(amsg.getPassword());
        kvo = dbf.persistAndRefresh(kvo);
        return kvo;
    }

    @Override
    public Host getHost(HostVO vo) {
        KVMHostVO kvo = dbf.findByUuid(vo.getUuid(), KVMHostVO.class);
        KVMHostContext context = getHostContext(vo.getUuid());
        if (context == null) {
            context = createHostContext(kvo);
        }
        return new KVMHost(kvo, context);
    }

    private List<String> getHostManagedByUs() {
        int qun = 10000;
        long amount = dbf.count(HostVO.class);
        int times = (int)(amount / qun) + (amount % qun != 0 ? 1 : 0);
        List<String> hostUuids = new ArrayList<String>();
        int start = 0;
        for (int i=0; i<times; i++) {
            SimpleQuery<KVMHostVO> q = dbf.createQuery(KVMHostVO.class);
            q.select(HostVO_.uuid);
            // disconnected host will be handled by HostManager
            q.add(HostVO_.status, SimpleQuery.Op.EQ, HostStatus.Connected);
            q.setLimit(qun);
            q.setStart(start);
            List<String> lst = q.listValue();
            start += qun;
            for (String huuid : lst) {
                if (!destMaker.isManagedByUs(huuid)) {
                    continue;
                }
                hostUuids.add(huuid);
            }
        }

        return hostUuids;
    }

    @Override
    public HypervisorType getHypervisorType() {
        return hypervisorType;
    }

    @Override
    public HostInventory getHostInventory(HostVO vo) {
        KVMHostVO kvo = vo instanceof KVMHostVO ? (KVMHostVO) vo : dbf.findByUuid(vo.getUuid(), KVMHostVO.class);
        return KVMHostInventory.valueOf(kvo);
    }

    @Override
    public HostInventory getHostInventory(String uuid) {
        KVMHostVO vo = dbf.findByUuid(uuid, KVMHostVO.class);
        return vo == null ? null : KVMHostInventory.valueOf(vo);
    }

    private void populateExtensions() {
        connectExtensions = pluginRgty.getExtensionList(KVMHostConnectExtensionPoint.class);
        for (KVMCompleteNicInformationExtensionPoint ext : pluginRgty.getExtensionList(KVMCompleteNicInformationExtensionPoint.class)) {
        	KVMCompleteNicInformationExtensionPoint old = completeNicInfoExtensions.get(ext.getL2NetworkTypeVmNicOn());
            if (old != null) {
                throw new CloudRuntimeException(String.format("duplicate KVMCompleteNicInformationExtensionPoint[%s, %s] for type[%s]",
                        old.getClass().getName(), ext.getClass().getName(), ext.getL2NetworkTypeVmNicOn()));
            }
        	completeNicInfoExtensions.put(ext.getL2NetworkTypeVmNicOn(), ext);
        }
    }
    
    public KVMCompleteNicInformationExtensionPoint getCompleteNicInfoExtension(L2NetworkType type) {
    	KVMCompleteNicInformationExtensionPoint extp = completeNicInfoExtensions.get(type);
    	if (extp == null) {
    		throw new IllegalArgumentException(String.format("unble to fine KVMCompleteNicInformationExtensionPoint supporting L2NetworkType[%s]", type));
    	}
    	return extp;
    }
    

    private void deployAnsibleModule() {
        if (CoreGlobalProperty.UNIT_TEST_ON) {
            return;
        }

        asf.deployModule(KVMConstant.ANSIBLE_MODULE_PATH, KVMConstant.ANSIBLE_PLAYBOOK_NAME);
    }
    
    @Override
    public boolean start() {
        deployAnsibleModule();
        populateExtensions();

        maxDataVolumeNum = KVMGlobalConfig.MAX_DATA_VOLUME_NUM.value(int.class);
        KVMGlobalConfig.MAX_DATA_VOLUME_NUM.installUpdateExtension(new GlobalConfigUpdateExtensionPoint() {
            @Override
            public void updateGlobalConfig(GlobalConfig oldConfig, GlobalConfig newConfig) {
                maxDataVolumeNum = newConfig.value(int.class);
            }
        });
        KVMGlobalConfig.RESERVED_MEMORY_CAPACITY.installValidateExtension(new GlobalConfigValidatorExtensionPoint() {
            @Override
            public void validateGlobalConfig(String category, String name, String oldValue, String value) throws GlobalConfigException {
                if (!SizeUtils.isSizeString(value)) {
                    throw new GlobalConfigException(String.format("%s only allows a size string. A size string is a number with suffix 'T/t/G/g/M/m/K/k/B/b' or without suffix, but got %s",
                            KVMGlobalConfig.RESERVED_MEMORY_CAPACITY.getCanonicalName(), value));
                }
            }
        });

        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    public List<KVMHostConnectExtensionPoint> getConnectExtensions() {
        return connectExtensions;
    }
    
    KVMHostContext createHostContext(KVMHostVO vo) {
        UriComponentsBuilder ub = UriComponentsBuilder.newInstance();
        ub.scheme(KVMGlobalProperty.AGENT_URL_SCHEME);
        ub.host(vo.getManagementIp());
        ub.port(KVMGlobalProperty.AGENT_PORT);
        if (!"".equals(KVMGlobalProperty.AGENT_URL_ROOT_PATH)) {
            ub.path(KVMGlobalProperty.AGENT_URL_ROOT_PATH);
        }
        String baseUrl = ub.build().toUriString(); 
        
        KVMHostContext context = new KVMHostContext();
        context.setInventory(KVMHostInventory.valueOf(vo));
        context.setBaseUrl(baseUrl);
        return context;
    }

    public KVMHostContext getHostContext(String hostUuid) {
        KVMHostVO kvo = dbf.findByUuid(hostUuid, KVMHostVO.class);
        return createHostContext(kvo);
    }

    @Override
    public void nodeJoin(String nodeId) {
    }

    @Override
    public void nodeLeft(String nodeId) {
    }

    @Override
    public void iAmDead(String nodeId) {
    }

    @Override
    public void iJoin(String nodeId) {
        if (CoreGlobalProperty.UNIT_TEST_ON) {
            return;
        }

        if (!asf.isModuleChanged(KVMConstant.ANSIBLE_PLAYBOOK_NAME)) {
            return;
        }

        // KVM hosts need to deploy new agent
        // connect hosts even if they are ConnectionState is Connected

        List<String> hostUuids = getHostManagedByUs();
        if (hostUuids.isEmpty()) {
            return;
        }

        logger.debug(String.format("need to connect kvm hosts because kvm agent changed, uuids:%s", hostUuids));

        List<ConnectHostMsg> msgs = new ArrayList<ConnectHostMsg>();
        for (String huuid : hostUuids) {
            ConnectHostMsg msg = new ConnectHostMsg();
            msg.setNewAdd(false);
            msg.setUuid(huuid);
            bus.makeTargetServiceIdByResourceUuid(msg, HostConstant.SERVICE_ID, huuid);
            msgs.add(msg);
        }

        bus.send(msgs, HostGlobalConfig.HOST_LOAD_PARALLELISM_DEGREE.value(Integer.class), new CloudBusSteppingCallback() {
            @Override
            public void run(NeedReplyMessage msg, MessageReply reply) {
                ConnectHostMsg cmsg = (ConnectHostMsg)msg;
                if (!reply.isSuccess()) {
                    logger.warn(String.format("failed to connect kvm host[uuid:%s], %s", cmsg.getHostUuid(), reply.getError()));
                } else {
                    logger.debug(String.format("successfully to connect kvm host[uuid:%s]", cmsg.getHostUuid()));
                }
            }
        });
    }

    @Override
    public String getHypervisorTypeForMaxDataVolumeNumberExtension() {
        return KVMConstant.KVM_HYPERVISOR_TYPE;
    }

    @Override
    public int getMaxDataVolumeNumber() {
        return maxDataVolumeNum;
    }
}
