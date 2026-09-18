// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.tool.impl;

import io.agents.bqaagent.ClawApplication;
import io.agents.bqaagent.FinishStatus;
import io.agents.bqaagent.R;
import io.agents.bqaagent.tool.BaseTool;
import io.agents.bqaagent.tool.ToolParameter;
import io.agents.bqaagent.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class FinishTool extends BaseTool {

    @Override
    public String getName() {
        return "finish";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_finish);
    }

    @Override
    public String getDescriptionEN() {
        return "End the current task and report its outcome. Set status=\"success\" when the goal is achieved, " +
                "status=\"failed\" when the task cannot be completed (explain why in summary), or " +
                "status=\"not_a_task\" when the user was only chatting or asking a question and no phone action was needed. " +
                "summary must contain the actual result or explanation for the user.";
    }

    @Override
    public String getDescriptionCN() {
        return "结束当前任务并报告结果。status=\"success\" 表示目标已达成；status=\"failed\" 表示任务无法完成（在 summary 中说明原因）；" +
                "status=\"not_a_task\" 表示用户只是聊天或提问、无需操作手机。summary 必须包含给用户看的实际结果或说明。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("status", "string",
                        "Outcome of the task: success (goal achieved), failed (cannot be completed), not_a_task (pure chat/question, no phone action needed)",
                        true, FinishStatus.VALUES),
                new ToolParameter("summary", "string",
                        "The actual result or explanation shown to the user", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String summary = requireString(params, "summary");
        return ToolResult.success(summary);
    }
}
