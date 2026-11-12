package com.xxl.ai.api.business.workflow.controller;

import com.xxl.ai.api.business.space.model.SpaceContext;
import com.xxl.ai.api.business.space.service.SpaceService;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowDTO;
import com.xxl.ai.api.business.workflow.model.dto.WorkflowExecutionDTO;
import com.xxl.ai.api.business.workflow.service.WorkflowService;
import com.xxl.sso.core.annotation.XxlSso;
import com.xxl.tool.response.PageModel;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工作流管理 Controller
 *
 * @author xxl-ai 2026-11-12
 */
@RestController
@RequestMapping("/workflow")
public class WorkflowController {

    @Resource
    private WorkflowService workflowService;

    @Resource
    private SpaceService spaceService;

    @RequestMapping("/pageList")
    @XxlSso(permission = "workflow:default")
    public Response<PageModel<WorkflowDTO>> pageList(HttpServletRequest request,
                                                    @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                                    @RequestParam(required = false, defaultValue = "0") int offset,
                                                    @RequestParam(required = false, defaultValue = "10") int pagesize,
                                                    String name,
                                                    Integer status) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        PageModel<WorkflowDTO> pageModel = workflowService.pageList(spaceResp.getData().getSpaceId(), offset, pagesize, name, status);
        return Response.ofSuccess(pageModel);
    }

    @RequestMapping("/load")
    @XxlSso(permission = "workflow:default")
    public Response<WorkflowDTO> load(HttpServletRequest request,
                                      @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                      @RequestParam("id") long id) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return workflowService.load(spaceResp.getData().getSpaceId(), id);
    }

    @PostMapping("/insert")
    @XxlSso(permission = "workflow:default")
    public Response<String> insert(HttpServletRequest request,
                                  @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                  @RequestBody WorkflowDTO workflowDTO) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return workflowService.insert(spaceResp.getData().getSpaceId(), workflowDTO);
    }

    @PostMapping("/update")
    @XxlSso(permission = "workflow:default")
    public Response<String> update(HttpServletRequest request,
                                  @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                  @RequestBody WorkflowDTO workflowDTO) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return workflowService.update(spaceResp.getData().getSpaceId(), workflowDTO);
    }

    @PostMapping("/delete")
    @XxlSso(permission = "workflow:default")
    public Response<String> delete(HttpServletRequest request,
                                  @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                  @RequestBody List<Long> ids) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return workflowService.delete(spaceResp.getData().getSpaceId(), ids);
    }

    @PostMapping("/updateStatus")
    @XxlSso(permission = "workflow:default")
    public Response<String> updateStatus(HttpServletRequest request,
                                        @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                        @RequestParam("id") long id,
                                        @RequestParam("status") int status) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return workflowService.updateStatus(spaceResp.getData().getSpaceId(), id, status);
    }

    @PostMapping("/run")
    @XxlSso(permission = "workflow:default")
    public Response<WorkflowExecutionDTO> run(HttpServletRequest request,
                                             @RequestHeader(value = "xxl-space-id", required = false) Integer spaceId,
                                             @RequestParam("id") long id,
                                             @RequestBody(required = false) Map<String, Object> inputs) {
        Response<SpaceContext> spaceResp = spaceService.checkSpace(request, spaceId);
        if (!spaceResp.isSuccess()) {
            return Response.ofFail(spaceResp.getMsg());
        }
        return workflowService.run(spaceResp.getData().getSpaceId(), id, inputs);
    }
}
