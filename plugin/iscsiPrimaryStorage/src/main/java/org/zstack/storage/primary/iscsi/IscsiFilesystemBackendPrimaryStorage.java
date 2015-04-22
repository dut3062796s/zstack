package org.zstack.storage.primary.iscsi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.util.UriComponentsBuilder;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.workflow.*;
import org.zstack.header.core.Completion;
import org.zstack.header.core.NopeCompletion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.image.ImageConstant.ImageMediaType;
import org.zstack.header.image.ImageInventory;
import org.zstack.header.message.Message;
import org.zstack.header.rest.JsonAsyncRESTCallback;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.storage.backup.*;
import org.zstack.header.storage.primary.*;
import org.zstack.header.vm.VmInstanceSpec.ImageSpec;
import org.zstack.header.volume.VolumeFormat;
import org.zstack.header.volume.VolumeInventory;
import org.zstack.header.volume.VolumeType;
import org.zstack.identity.AccountManager;
import org.zstack.storage.primary.PrimaryStorageBase;
import org.zstack.storage.primary.PrimaryStorageManager;
import org.zstack.storage.primary.iscsi.IscsiFileSystemBackendPrimaryStorageCommands.*;
import org.zstack.utils.path.PathUtil;

import java.util.Map;

/**
 * Created by frank on 4/19/2015.
 */
public class IscsiFilesystemBackendPrimaryStorage extends PrimaryStorageBase {
    @Autowired
    private RESTFacade restf;
    @Autowired
    private IscsiFileSystemBackendPrimaryStorageFactory factory;
    @Autowired
    private AccountManager acntMgr;
    @Autowired
    private PrimaryStorageManager psMgr;

    public IscsiFilesystemBackendPrimaryStorage(IscsiFileSystemBackendPrimaryStorageVO vo) {
        super(vo);
    }

    protected IscsiFileSystemBackendPrimaryStorageVO getSelf() {
        return (IscsiFileSystemBackendPrimaryStorageVO) self;
    }

    @Override
    protected IscsiFileSystemBackendPrimaryStorageInventory getSelfInventory() {
        return IscsiFileSystemBackendPrimaryStorageInventory.valueOf(getSelf());
    }

    @Override
    protected void handle(InstantiateVolumeMsg msg) {
        if (msg instanceof InstantiateRootVolumeFromTemplateMsg) {
            handle((InstantiateRootVolumeFromTemplateMsg) msg);
        } else {
            createEmptyVolume(msg);
        }
    }

    private String makeHttpUrl(String path) {
        UriComponentsBuilder ub = UriComponentsBuilder.newInstance();
        ub.scheme("http");
        ub.host(getSelf().getHostname());
        ub.port(IscsiFileSystemBackendPrimaryStorageGlobalProperty.AGENT_PORT);
        ub.path(getSelf().getFilesystemType());
        ub.path(path);
        return ub.build().toUriString();
    }

    private String makePathInImageCache(String imageUuid) {
        return PathUtil.join(self.getUrl(), "imageCache/templates", imageUuid, String.format("%s.template", imageUuid));
    }

    private String makeRootVolumePath(String volumeUuid) {
        return PathUtil.join(self.getUrl(), "rootVolumes", String.format("acct-%s", acntMgr.getOwnerAccountUuidOfResource(volumeUuid)),
                String.format("vol-%s", volumeUuid), String.format("%s.img", volumeUuid));
    }

    private String makeDataVolumePath(String volumeUuid) {
        return PathUtil.join(self.getUrl(), "dataVolumes", String.format("acct-%s", acntMgr.getOwnerAccountUuidOfResource(volumeUuid)),
                String.format("vol-%s", volumeUuid), String.format("%s.img", volumeUuid));
    }

    private String makeIscsiVolumePath(String target) {
        return String.format("iscsi://ip-%s:3260-iscsi-%s-lun-1", getSelf().getUuid(), target);
    }

    private void deleteBits(String installPath, String volumeUuid, final Completion completion) {
        DeleteBitsCmd cmd = new DeleteBitsCmd();
        cmd.setInstallPath(installPath);
        cmd.setVolumeUuid(volumeUuid);
        restf.asyncJsonPost(makeHttpUrl(IscsiBtrfsPrimaryStorageConstants.DELETE_BITS_EXISTENCE), cmd, new JsonAsyncRESTCallback<DeleteBitsRsp>() {
            @Override
            public void fail(ErrorCode err) {
                completion.fail(err);
            }

            @Override
            public void success(DeleteBitsRsp ret) {
                if (ret.isSuccess()) {
                    reportCapacity(ret);
                    completion.success();
                } else {
                    completion.fail(errf.stringToOperationError(ret.getError()));
                }
            }

            @Override
            public Class<DeleteBitsRsp> getReturnClass() {
                return DeleteBitsRsp.class;
            }
        });
    }

    private void handle(final InstantiateRootVolumeFromTemplateMsg msg) {
        final InstantiateVolumeReply reply = new InstantiateVolumeReply();
        final ImageSpec ispec = msg.getTemplateSpec();

        BackupStorageVO bs = dbf.findByUuid(ispec.getSelectedBackupStorage().getBackupStorageUuid(), BackupStorageVO.class);
        final BackupStorageInventory bsinv = BackupStorageInventory.valueOf(bs);

        final VolumeInventory volume = msg.getVolume();
        final ImageInventory image = ispec.getInventory();
        if (ImageMediaType.RootVolumeTemplate.toString().equals(image.getMediaType())) {
            FlowChain chain = FlowChainBuilder.newShareFlowChain();
            chain.setName(String.format("create-root-volume-from-image-%s", image.getUuid()));
            chain.then(new ShareFlow() {
                String imagePathInCache;
                String volumePath = makeRootVolumePath(volume.getUuid());
                String iscsiTarget;

                @Override
                public void setup() {
                    flow(new NoRollbackFlow() {
                        String __name__ = String.format("download-image-%s-to-cache-ps-%s", image.getUuid(), self.getUuid());

                        @Override
                        public void run(FlowTrigger trigger, Map data) {
                            SimpleQuery<ImageCacheVO> query = dbf.createQuery(ImageCacheVO.class);
                            query.add(ImageCacheVO_.primaryStorageUuid, Op.EQ, self.getUuid());
                            query.add(ImageCacheVO_.imageUuid, Op.EQ, image.getUuid());
                            ImageCacheVO cvo = query.find();
                            if (cvo != null) {
                                checkCache(cvo, trigger);
                            } else {
                                downloadToCache(null, trigger);
                            }
                        }

                        private void downloadToCache(final ImageCacheVO cvo, final FlowTrigger trigger) {
                            IscsiFileSystemBackendPrimaryToBackupStorageMediator mediator = factory.getPrimaryToBackupStorageMediator(BackupStorageType.valueOf(bsinv.getType()),
                                    VolumeFormat.getMasterHypervisorTypeByVolumeFormat(image.getFormat()));

                            imagePathInCache = makePathInImageCache(image.getUuid());
                            mediator.downloadBits(getSelfInventory(), bsinv,
                                    ispec.getSelectedBackupStorage().getInstallPath(),  imagePathInCache, new Completion() {
                                        @Override
                                        public void success() {
                                            if (cvo == null) {
                                                ImageCacheVO vo = new ImageCacheVO();
                                                vo.setImageUuid(image.getUuid());
                                                vo.setInstallUrl(imagePathInCache);
                                                vo.setMediaType(ImageMediaType.valueOf(image.getMediaType()));
                                                vo.setPrimaryStorageUuid(self.getUuid());
                                                vo.setSize(image.getSize());
                                                vo.setState(ImageCacheState.ready);
                                                vo.setMd5sum("not calculated");
                                                dbf.persist(vo);
                                            } else {
                                                cvo.setInstallUrl(imagePathInCache);
                                                dbf.update(cvo);
                                            }

                                            trigger.next();
                                        }

                                        @Override
                                        public void fail(ErrorCode errorCode) {
                                            trigger.fail(errorCode);
                                        }
                                    });
                        }

                        private void checkCache(final ImageCacheVO cvo, final FlowTrigger trigger) {
                            CheckBitsExistenceCmd cmd = new CheckBitsExistenceCmd();
                            cmd.setPath(cvo.getInstallUrl());
                            restf.asyncJsonPost(makeHttpUrl(IscsiBtrfsPrimaryStorageConstants.CHECK_BITS_EXISTENCE), cmd, new JsonAsyncRESTCallback<CheckBitsExistenceRsp>(trigger) {
                                @Override
                                public void fail(ErrorCode err) {
                                    trigger.fail(err);
                                }

                                @Override
                                public void success(CheckBitsExistenceRsp ret) {
                                    if (!ret.isSuccess()) {
                                        trigger.fail(errf.stringToOperationError(ret.getError()));
                                    }  else {
                                        if (ret.isExisting()) {
                                            imagePathInCache = cvo.getInstallUrl();
                                            trigger.next();
                                        } else {
                                            downloadToCache(cvo, trigger);
                                        }
                                    }
                                }

                                @Override
                                public Class<CheckBitsExistenceRsp> getReturnClass() {
                                    return CheckBitsExistenceRsp.class;
                                }
                            });
                        }
                    });

                    flow(new Flow() {
                        String __name__ = String.format("create-volume-%s-from-image-%s", volume.getUuid(), image.getUuid());

                        public void run(final FlowTrigger trigger, Map data) {
                            CreateRootVolumeFromTemplateCmd cmd = new CreateRootVolumeFromTemplateCmd();
                            cmd.setInstallPath(volumePath);
                            cmd.setVolumeUuid(volume.getUuid());
                            cmd.setChapPassword(getSelf().getChapPassword());
                            cmd.setChapUsername(getSelf().getChapUsername());
                            cmd.setTemplatePathInCache(imagePathInCache);

                            restf.asyncJsonPost(makeHttpUrl(IscsiBtrfsPrimaryStorageConstants.CREATE_ROOT_VOLUME_PATH),
                                    cmd, new JsonAsyncRESTCallback<CreateRootVolumeFromTemplateRsp>() {
                                        @Override
                                        public void fail(ErrorCode err) {
                                            trigger.fail(err);
                                        }

                                        @Override
                                        public void success(CreateRootVolumeFromTemplateRsp ret) {
                                            if (!ret.isSuccess()) {
                                                volumePath = null;
                                                trigger.fail(errf.stringToOperationError(ret.getError()));
                                            } else {
                                                iscsiTarget = makeIscsiVolumePath(ret.getIscsiPath());
                                                reportCapacity(ret);
                                                trigger.next();
                                            }
                                        }

                                        @Override
                                        public Class<CreateRootVolumeFromTemplateRsp> getReturnClass() {
                                            return CreateRootVolumeFromTemplateRsp.class;
                                        }
                                    });
                        }

                        @Override
                        public void rollback(FlowTrigger trigger, Map data) {
                            if (volumePath != null) {
                                deleteBits(volumePath, volume.getUuid(), new NopeCompletion());
                            }

                            trigger.rollback();
                        }
                    });

                    done(new FlowDoneHandler(msg) {
                        @Override
                        public void handle(Map data) {
                            volume.setInstallPath(iscsiTarget);
                            reply.setVolume(volume);
                            bus.reply(msg, reply);
                        }
                    });

                    error(new FlowErrorHandler(msg) {
                        @Override
                        public void handle(ErrorCode errCode, Map data) {
                            reply.setError(errCode);
                            bus.reply(msg, reply);
                        }
                    });
                }
            }).start();
        } else {
            createEmptyVolume(msg);
        }
    }

    private void reportCapacity(AgentCapacityResponse ret) {
        if (ret.getTotalCapacity() != null && ret.getAvailableCapacity() != null) {
            psMgr.sendCapacityReportMessage(ret.getTotalCapacity(), ret.getAvailableCapacity(), self.getUuid());
        }
    }

    private void createEmptyVolume(final InstantiateVolumeMsg msg) {
        final VolumeInventory vol = msg.getVolume();
        CreateEmptyVolumeCmd cmd = new CreateEmptyVolumeCmd();
        final InstantiateVolumeReply reply = new InstantiateVolumeReply();

        final String volPath;
        if (VolumeType.Root.toString().equals(vol.getType())) {
            volPath = makeRootVolumePath(vol.getUuid());
        } else {
            volPath = makeDataVolumePath(vol.getUuid());
        }

        cmd.setInstallPath(volPath);
        cmd.setVolumeUuid(vol.getUuid());
        cmd.setChapUsername(getSelf().getChapUsername());
        cmd.setChapPassword(getSelf().getChapPassword());
        cmd.setSize(vol.getSize());
        restf.asyncJsonPost(makeHttpUrl(IscsiBtrfsPrimaryStorageConstants.CREATE_EMPTY_VOLUME_PATH), cmd, new JsonAsyncRESTCallback<CreateEmptyVolumeRsp>() {
            @Override
            public void fail(ErrorCode err) {
                reply.setError(err);
                bus.reply(msg ,reply);
            }

            @Override
            public void success(CreateEmptyVolumeRsp ret) {
                if (!ret.isSuccess()) {
                    reply.setError(errf.stringToOperationError(ret.getError()));
                } else {
                    vol.setInstallPath(makeIscsiVolumePath(ret.getIscsiPath()));
                    reply.setVolume(vol);
                }

                bus.reply(msg, reply);
            }

            @Override
            public Class<CreateEmptyVolumeRsp> getReturnClass() {
                return CreateEmptyVolumeRsp.class;
            }
        });
    }

    @Override
    protected void handle(final DeleteVolumeOnPrimaryStorageMsg msg) {
        final DeleteVolumeOnPrimaryStorageReply reply = new DeleteVolumeOnPrimaryStorageReply();
        deleteBits(msg.getVolume().getInstallPath(), msg.getVolume().getUuid(), new Completion(msg) {
            @Override
            public void success() {
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
            }
        });
    }

    @Override
    protected void handle(CreateTemplateFromVolumeOnPrimaryStorageMsg msg) {

    }

    @Override
    protected void handle(DownloadDataVolumeToPrimaryStorageMsg msg) {

    }

    @Override
    protected void handle(final DeleteBitsOnPrimaryStorageMsg msg) {
        final DeleteBitsOnPrimaryStorageReply reply = new DeleteBitsOnPrimaryStorageReply();
        deleteBits(msg.getInstallPath(), null, new Completion(msg) {
            @Override
            public void success() {
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
            }
        });
    }

    @Override
    protected void handle(UploadVolumeFromPrimaryStorageToBackupStorageMsg msg) {

    }
    
    @Override
    protected void handleLocalMessage(Message msg) {
        if (msg instanceof ConnectPrimaryStorageMsg) {
            handle((ConnectPrimaryStorageMsg)msg);
        } else {
            super.handleLocalMessage(msg);
        }
    }

    private void handle(final ConnectPrimaryStorageMsg msg) {
        final ConnectPrimaryStorageReply reply = new ConnectPrimaryStorageReply();
        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("connect-iscsi-file-system-primary-storage-%s", self.getUuid()));
        chain.then(new ShareFlow() {
            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = String.format("deploy-agent-to-iscsi-filesystem-primary-storage-%s", self.getUuid());
                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        trigger.next();
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = String.format("init-iscsi-filesystem-primary-storage-%s", self.getUuid());

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        InitCmd cmd = new InitCmd();
                        cmd.setRootFolderPath(getSelf().getUrl());
                        restf.asyncJsonPost(makeHttpUrl(IscsiBtrfsPrimaryStorageConstants.INIT_PATH), cmd, new JsonAsyncRESTCallback<InitRsp>() {
                            @Override
                            public void fail(ErrorCode err) {
                                trigger.fail(err);
                            }

                            @Override
                            public void success(InitRsp ret) {
                                if (ret.isSuccess()) {
                                    reportCapacity(ret);
                                    trigger.next();
                                } else {
                                    trigger.fail(errf.stringToOperationError(ret.getError()));
                                }
                            }

                            @Override
                            public Class<InitRsp> getReturnClass() {
                                return InitRsp.class;
                            }
                        });
                    }
                });

                done(new FlowDoneHandler(msg) {
                    @Override
                    public void handle(Map data) {
                        reply.setConnected(true);
                        bus.reply(msg, reply);
                    }
                });

                error(new FlowErrorHandler(msg) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        reply.setError(errCode);
                        bus.reply(msg, reply);
                    }
                });
            }
        }).start();
    }
}
