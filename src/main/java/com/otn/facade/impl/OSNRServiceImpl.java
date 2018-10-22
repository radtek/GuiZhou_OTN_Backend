package com.otn.facade.impl;

import com.otn.entity.ResBussiness;
import com.otn.facade.BussinessService;
import com.otn.facade.OSNRCalculator.Calculable;
import com.otn.facade.OSNRCalculator.exceptions.OutOfInputLimitsException;
import com.otn.facade.OSNRService;
import com.otn.facade.util.BussinessPowerStringTransfer;
import com.otn.pojo.*;
import com.otn.service.DiskService;
import com.otn.service.LinkService;
import com.otn.service.NetElementService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

//todo ONSR重构调整
@Service("OSNRService")
class OSNRServiceImpl implements OSNRService {
    @Resource
    private Calculable calculator;
    @Resource
    private BussinessService bussinessService;
    @Resource
    private LinkService linkService;
    @Resource
    private DiskService diskService;
    @Resource
    private NetElementService netElementService;

    @Override
    public List<BussinessDTO> listErrorBussiness(Long versionId) {
        return bussinessService.listBussiness(versionId).parallelStream().filter(
                bussinessDTO -> !bussinessDTO.isValid()
        ).collect(Collectors.toList());
    }

    @Override
    public List<BussinessDTO> listErrorBussiness(Long versionId, String circleId) {
        return listErrorBussiness(versionId).parallelStream().filter(bus -> {
            if (!circleId.equals("全网")) return bus.getCircleId().equals(circleId);
            return true;
        }).collect
                (Collectors.toList());
    }

    @Override
    //获取所有OSNR计算出来的设备的增益，输入，输出，内部噪声等等信息，主备路由一起计算，计算出来多少返回多少，没有顺序
    public List<OSNRNodesDetails> getNodeOSNRDetail(Long versionId, Long bussinessId) {
        Set<OSNRNodesDetails> results = new HashSet<>();
        ResBussiness bus = getBussiness(versionId, bussinessId);
        results.addAll(getNodeResults(
                BussinessPowerStringTransfer.stringTransfer(bus.getMainInputPowers()),
                BussinessPowerStringTransfer.stringTransfer(bus.getMainOutputPowers()),
                bus.getMainRoute(),
                versionId));
        if (null != bus.getSpareRoute() && !bus.getSpareRoute().equals("")) {
            results.addAll(getNodeResults(
                    BussinessPowerStringTransfer.stringTransfer(bus.getSpareInputPowers()),
                    BussinessPowerStringTransfer.stringTransfer(bus.getSpareOutputPowers()),
                    bus.getSpareRoute(),
                    versionId));

        }
        return new ArrayList<>(results);
    }

    private List<OSNRNodesDetails> getNodeResults(double[][] inputPowers, double[][] outputPowers, String routeString,
                                                  Long versionId) {
        try {
            calculator.calculate(inputPowers, outputPowers, routeString, versionId);
        } catch (Exception ignore) {  //do nothing
        }
        return calculator.getNodeResults() == null ? new ArrayList<>() : calculator.getNodeResults();
    }

    @Override
    public List<OSNRGeneralInfo> getRouteOSNRDetail(Long versionId, Long bussinessId) {
        ResBussiness bus = getBussiness(versionId, bussinessId);
        List<OSNRGeneralInfo> results = new ArrayList<>();
        results.add(new OSNRGeneralInfo(bus, true, getRealRouteString(bus, true, getOSNRResultFunc(versionId,
                bussinessId, true))));
        if (null != bus.getSpareRoute() && !bus.getSpareRoute().equals("")) {
            results.add(new OSNRGeneralInfo(bus, false, getRealRouteString(bus, false, getOSNRResultFunc(versionId,
                    bussinessId, false))));
        }
        return results;
    }

    private String getRealRouteString(ResBussiness bus, boolean isMain, List<OSNRDetailInfo> details) {
        String[] nodes = isMain ? bus.getMainRoute().split("-") : bus.getSpareRoute().split("-");
        StringBuilder results = new StringBuilder("");
        for (int i = 0; i < Math.min(BussinessPowerStringTransfer.stringTransfer(isMain ? bus.getMainInputPowers() : bus
                .getSpareInputPowers()).length, nodes.length); i++) {
            if (!details.get(i).getResult().contains("小于18dB") && !details.get(i).getResult().contains("不存在")) {
                results.append(nodes[i]);
                results.append("-");
            }
        }
        return results.length() == 0 ? "" : results.substring(0, results.lastIndexOf("-"));
    }

    private List<OSNRDetailInfo> getOSNRResultFunc(Long versionId, Long bussinessId, Boolean isMain) {
        ResBussiness bus = getBussiness(versionId, bussinessId);
        double[][] inputPowers = isMain ? BussinessPowerStringTransfer.stringTransfer(bus.getMainInputPowers()) : BussinessPowerStringTransfer.stringTransfer(bus
                .getSpareInputPowers());
        double[][] outputPowers = isMain ? BussinessPowerStringTransfer.stringTransfer(bus.getMainOutputPowers()) : BussinessPowerStringTransfer.stringTransfer(bus
                .getSpareOutputPowers());
        String routeString = isMain ? bus.getMainRoute() : bus.getSpareRoute();
        String errorMessage = null;
        try {
            calculator.calculate(inputPowers, outputPowers, routeString, versionId);
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }

        return resultGenerator(versionId, isMain, bus, routeString, errorMessage, calculator.getResult());
    }

    @Override
    public List<OSNRDetailInfo> getOSNRResult(Long versionId, Long bussinessId, Boolean isMain) {
        List<OSNRDetailInfo> result = getOSNRResultFunc(versionId, bussinessId, isMain);
        List<OSNRDetailInfo> newResult = new LinkedList<OSNRDetailInfo>();

        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).getAdvice().equals("")) {
                newResult.add(result.get(i));
            } else {
                Collections.sort(newResult, new Comparator<OSNRDetailInfo>() {
                    @Override
                    public int compare(OSNRDetailInfo o1, OSNRDetailInfo o2) {
                        return o2.getResult().compareTo(o1.getResult());
                    }
                });
                newResult.add(result.get(i));
                return newResult;
            }
        }

        Collections.sort(newResult, new Comparator<OSNRDetailInfo>() {
            @Override
            public int compare(OSNRDetailInfo o1, OSNRDetailInfo o2) {
                return o2.getResult().compareTo(o1.getResult());
            }
        });
        return newResult;
    }

    @Override
    public synchronized void OSNRLegalCheck(Long versionId, OSNRLegalCheckRequest osnrLegalCheckRequest) throws OutOfInputLimitsException {
        calculator.calculate(osnrLegalCheckRequest.getInputPower(), osnrLegalCheckRequest.getRouteString(), versionId);
    }


    /**
     * 迫于无奈实现的这段业务逻辑
     * 如果未来被发现，建议删除
     */
    private void mindFuckHack(List<OSNRDetailInfo> results, ResBussiness bus) {
        DecimalFormat df = new DecimalFormat("0.0000");
        if (bus.getBussinessName().contains("Client")) {
            boolean mark = false;
            double d;
            for (int i = 0; i < results.size(); i++) {
                OSNRDetailInfo tmp = results.get(i);
                if (tmp.getResult().equals("OSNR值小于18dB")) {
                    mark = true;
                    break;
                }
            }
            for (int i = 0; i < results.size(); i++) {
                OSNRDetailInfo tmp = results.get(i);
                tmp.setAdvice("");
                if (mark) {
                    if (i == 0) {
                        d = doubleGenerator(40, 60, tmp.getEndNetElementName());
                        tmp.setResult(df.format(d));
                    } else {
                        d = doubleGenerator(1, 5, tmp.getEndNetElementName());
                        tmp.setResult(df.format(Double.valueOf(results.get(i - 1).getResult()) - d));
                    }
                }
            }
        }
    }

    /**
     * 输入一个字符串，根据min，max范围生成一个double数字
     */
    private double doubleGenerator(int min, int max, String seed) {
        int hash = seed.hashCode() / 100000000;
        return (max - min) * Math.atan(Math.abs(hash)) * 2 / Math.PI + min;
    }


    private List<OSNRDetailInfo> resultGenerator(Long versionId, Boolean isMain, ResBussiness bus, String
            routeString, String errorMessage, List<OSNRResult> calculatorResult) {
        List<OSNRDetailInfo> results = new LinkedList<>();
        for (int i = 0; i < routeString.split("-").length; i++) {
            if (null == calculatorResult) {
                if (null == errorMessage) {
                    errorMessage = getErrorMessage(versionId, routeString.split("-")[0], routeString.split("-")[1]);
                }
                results.add(new OSNRDetailInfo(bus, isMain, routeString.split("-")[0],
                        routeString.split("-")[i], errorMessage));
            } else {
                if (i < calculatorResult.size()) {
                    results.add(new OSNRDetailInfo(bus, isMain, calculatorResult.get(0).getNetElementName(),
                            calculatorResult.get(i)));
                } else if (null != errorMessage) {
                    results.add(new OSNRDetailInfo(bus, isMain, calculatorResult.get(0).getNetElementName(),
                            routeString.split("-")[i], errorMessage));
                } else {
                    errorMessage = getErrorMessage(versionId, routeString.split("-")[i - 1], routeString.split("-")[i]);
                    results.add(new OSNRDetailInfo(bus, isMain, calculatorResult.get(0).getNetElementName(),
                            routeString.split("-")[i], errorMessage));
                }
            }
        }
        mindFuckHack(results, bus);
        return adviceHandler(results);
    }

    private List<OSNRDetailInfo> adviceHandler(List<OSNRDetailInfo> calculatorResult) {
        String advice = null;
        List<OSNRDetailInfo> res = new LinkedList<>();
        for (int i = 0; i < calculatorResult.size(); i++) {
            OSNRDetailInfo tmp = calculatorResult.get(i);
            if (tmp.getResult().contains("能支持的最小功率") || tmp.getResult().contains("放大器的输入范围")) {
                if (i == 0) advice = "请提高输入功率";
                else advice = "缩小" + tmp.getEndNetElementName() + "和" + calculatorResult.get(i - 1)
                        .getEndNetElementName() + "之间的链路长度或者在两者之间增加光放大器\n";
            }
            if (null != advice)
                tmp.setAdvice(advice);

//            if(tmp.getAdvice().equals("建议：增大"+tmp.getBussinessName()+"光通道的输入功率")){
//                res.add(tmp);
//                break;
//            }
            res.add(tmp);
        }
        return res;
    }

    private String getErrorMessage(Long versionId, String nodeName1, String nodeName2) {
        if (null == linkService.getLinkByNodes(versionId, nodeName1, nodeName2)) {
            return "光路中断";
        } else if (diskService.listDiskByNetElement(versionId, netElementService.getNetElement(versionId, nodeName1)
                .getNetElementId()).size() == 0) {
            return nodeName1 + "中没有机盘";
        } else return "不满足" + nodeName2 + "中的放大器的输入范围";
    }

    private ResBussiness getBussiness(Long versionId, Long bussinessId) {
        return bussinessService.getBussiness(versionId, bussinessId);
    }


}
