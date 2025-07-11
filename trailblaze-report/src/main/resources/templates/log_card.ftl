<#-- Shared log card partial for Trailblaze logs -->
<div class="log-card-container" style="margin-bottom: 10px;">
    <div style="text-align: center; margin-bottom: 4px;">
        <div style="background: #e3f2fd; color: #222; border-radius: 16px; padding: 4px 14px; font-size: 0.95em; font-weight: 500; box-shadow: 0 1px 4px rgba(0,0,0,0.04); display: inline-block;">
            <strong>Time Elapsed:</strong>
            <span>${(log.timestamp.toEpochMilliseconds() - logs[0].timestamp.toEpochMilliseconds())?number_to_date?string("mm:ss")}</span>
        </div>
    </div>
    <div class="col">
        <div class="card shadow-sm">
            <div style="position: relative;">
                <#-- Pastel color palette for log types: visually distinct, no reds, easy to adjust
                    MAESTRO_DRIVER:      #a3c9f9   (pastel blue)
                    MAESTRO_COMMAND:     #c3aed6   (pastel purple)
                    DELEGATING_TRAILBLAZE_TOOL: #ffe5b4 (pastel peach)
                    TRAILBLAZE_COMMAND:  #b4f8c8   (pastel mint)
                    LLM_REQUEST:         #b5ead7   (pastel aquamarine)
                    AGENT_TASK_STATUS:   #f7d6e0   (pastel pink)
                    SESSION_STATUS:      #f9f7cf   (pastel yellow)
                    OBJECTIVE_START:     #f5b042   (pastel orange)
                    OBJECTIVE_COMPLETE:  #f49ac2   (pastel magenta/pink)
                    TOP_LEVEL_MAESTRO_COMMAND: #a0e7e5 (pastel cyan)
                    SHELLBLADES_TOOL:    #d4a5a5   (pastel brown)
                    SHELLBLADES_COMMAND: #b5ead7   (pastel aquamarine)
                -->
                <#assign logColors = {
                "MAESTRO_DRIVER": "#a3c9f9",
                "MAESTRO_COMMAND": "#c3aed6",
                "DELEGATING_TRAILBLAZE_TOOL": "#ffe5b4",
                "TRAILBLAZE_COMMAND": "#b4f8c8",
                "LLM_REQUEST": "#b5ead7",
                "AGENT_TASK_STATUS": "#f7d6e0",
                "SESSION_STATUS": "#f9f7cf",
                "OBJECTIVE_START": "#f5b042",
                "OBJECTIVE_COMPLETE": "#f49ac2",
                "TOP_LEVEL_MAESTRO_COMMAND": "#a0e7e5",
                "SHELLBLADES_TOOL": "#d4a5a5",
                "SHELLBLADES_COMMAND": "#b5ead7"
                }>
                <#assign logColor = logColors[log.type]!"#000000"> <!-- fallback color -->
                <div style="height: 5px; background-color: ${logColor};"></div>
                <span class="text-center"
                      style="display: block; font-size: .6em; font-weight: bold; background-color: ${logColor};">
                     <#if log.type == "MAESTRO_DRIVER">
                         <span>${log.type} (${log.action.class.simpleName})</span>
                        <#else>
                         <span>${log.type}</span>
                     </#if>
                </span>

                <#if log.screenshotFile??>
                    <div style="position: relative;">
                        <img id="screenshot-${log_index}"
                             src="${log.screenshotFile}"
                             class="bd-placeholder-img card-img"
                             style="object-fit:contain; border: 1px solid black;" alt="Screenshot">
                    </div>
                </#if>
                <div class="p-1">
                    <#if log.type == "TOP_LEVEL_MAESTRO_COMMAND">
                        <pre class="small"><b>Top-Level Maestro Command:</b> ${log.command?html}</pre>
                    <#elseif log.type == "MAESTRO_COMMAND">
                        <pre class="small">${TemplateHelpers.asMaestroYaml(log)?html}</pre>
                    <#elseif log.type == "TRAILBLAZE_COMMAND">
                        <pre class="small">${TemplateHelpers.asCommandJson(log)?html}</pre>
                    <#elseif log.type == "DELEGATING_TRAILBLAZE_TOOL">
                        <pre class="small">${TemplateHelpers.asCommandJson(log)?html}</pre>
                    <#elseif log.type == "AGENT_TASK_STATUS">
                        <h6 class="font-weight-bold">${log.agentTaskStatus.class.simpleName}</h6>
                        <pre class="small">${log.agentTaskStatus.statusData.prompt?html}</pre>
                    <#elseif log.type == "SESSION_STATUS">
                        <pre class="small">${log.sessionStatus?html}</pre>
                    <#elseif log.type == "OBJECTIVE_START">
                        <b>Objective Start:</b>
                        <pre class="small">${log.description?html}</pre>
                    <#elseif log.type == "OBJECTIVE_COMPLETE">
                        <b>Objective Complete:</b>
                        <pre class="small">${log.description?html}</pre>
                        <#if log.objectiveResult??>
                            <b>Result:</b>
                            <pre class="small">${log.objectiveResult.llmExplanation?html}</pre>
                        </#if>
                    </#if>
                </div>
                <#if log.type == "MAESTRO_DRIVER">
                    <#if log.action.x?? && log.action.y??>
                        <script>
                            drawScreenshotCircle(${log_index}, ${log.deviceWidth?c}, ${log.deviceHeight?c}, ${log.action.x?c}, ${log.action.y?c});
                        </script>
                    </#if>
                </#if>
            </div>
            <div class="card-body">
                <!-- Time Elapsed moved above the card -->
                <div class=" text-center">
                    <button type="button" class="btn btn-custom-sm" data-bs-toggle="modal"
                            data-bs-target="#logModal${log_index}">
                        View Details
                    </button>
                </div>
                <!-- Modal -->
                <div class="modal fade" id="logModal${log_index}" tabindex="-1"
                     aria-labelledby="logModalLabel${log_index}" aria-hidden="true">
                    <div class="modal-dialog modal-lg">
                        <div class="modal-content">
                            <div class="modal-header" style="background-color: ${logColor}; color: #222;">
                                <h5 class="modal-title" id="logModalLabel${log_index}" style="font-weight: bold; letter-spacing: 0.5px;">${log.type?replace('_', ' ')?cap_first}</h5>
                                <button type="button" class="close" data-bs-dismiss="modal"
                                        aria-label="Close" style="filter: invert(0.7);">
                                    <span aria-hidden="true">&times;</span>
                                </button>
                            </div>
                            <div class="modal-body">
                                <#if log.type == 'MAESTRO_DRIVER'>
                                    <h5>Class Name</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.action.class.simpleName?string?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                    <h5>Raw Log</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.toString()?string?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                <#elseif log.type == 'LLM_REQUEST'>
                                    <h5>LLM Response</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.llmResponse[0]?string?replace('(?m)^\\s+', '', 'r')?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                    <h5>Request Duration</h5>
                                    <div style="white-space: normal; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">
                                        ${(log.durationMs)?number_to_date?string("mm:ss")?replace('^\\s+|\\s+$', '', 'r')?html}
                                    </div>
                                    <#if log.llmMessage??>
                                        <h5>LLM Message</h5>
                                        <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.llmMessage?string?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                    </#if>
                                    <h5>Actions Returned</h5>
                                    <#list log.actions as action>
                                        <#if action?is_hash || action?is_sequence>
                                            <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; max-height: 300px; overflow: auto; margin-bottom: 8px;">${action?json_string?replace('(?m)^\\s+', '', 'r')?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                        <#else>
                                            <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; max-height: 300px; overflow: auto; margin-bottom: 8px;">${action?string?replace('(?m)^\\s+', '', 'r')?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                        </#if>
                                    </#list>
                                    <#if log.llmMessages?? && (log.llmMessages?size > 0)>
                                        <h5>Chat History</h5>
                                        <#list log.llmMessages as message>
                                            <#if message.message??>
                                                <#assign cleanMessage = message.message?replace('data:image/png;base64,[A-Za-z0-9+/=]+', '[screenshot removed]', 'r')?replace('^\\s+|\\s+$', '', 'r')>
                                                <div class="mb-2">
                                                    <span class="badge bg-secondary">${message.role?cap_first}</span>
                                                    <div class="border rounded p-2 mt-1" style="background-color: <#if message.role == 'user'>#e3f2fd<#elseif message.role == 'assistant'>#f1f8e9<#else>#f8bbd0</#if>;">
                                                        <pre style="white-space: pre-wrap; word-break: break-word; margin: 0;">${cleanMessage?html}</pre>
                                                    </div>
                                                </div>
                                            </#if>
                                        </#list>
                                    <#else>
                                        <h5>Chat History</h5>
                                        <div class="text-muted">No chat history available.</div>
                                    </#if>
                                <#elseif log.type == 'TRAILBLAZE_COMMAND'>
                                    <h5>Trailblaze Command (JSON)</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${TemplateHelpers.asCommandJson(log)?html}</pre>
                                <#elseif log.type == 'DELEGATING_TRAILBLAZE_TOOL'>
                                    <h5>Delegating Trailblaze Tool (JSON)</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${TemplateHelpers.asCommandJson(log)?html}</pre>
                                <#elseif log.type == 'MAESTRO_COMMAND'>
                                    <h5>Maestro Command (YAML)</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${TemplateHelpers.asMaestroYaml(log)?html}</pre>
                                <#elseif log.type == 'TOP_LEVEL_MAESTRO_COMMAND'>
                                    <h5>Top-Level Maestro Command</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.command?html}</pre>
                                <#elseif log.type == 'AGENT_TASK_STATUS'>
                                    <h5>Agent Task Status</h5>
                                    <div><b>Status Type:</b> ${log.agentTaskStatus.class.simpleName}</div>
                                    <h6>Prompt</h6>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.agentTaskStatus.statusData.prompt?html}</pre>
                                <#elseif log.type == 'SESSION_STATUS'>
                                    <h5>Session Status</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.sessionStatus?html}</pre>
                                <#elseif log.type == 'OBJECTIVE_START'>
                                    <h5>Objective Start</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.description?string?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                <#elseif log.type == 'OBJECTIVE_COMPLETE'>
                                    <h5>Objective Complete</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.description?string?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                    <#if log.objectiveResult??>
                                        <h5>Result</h5>
                                        <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.objectiveResult.llmExplanation?string?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                        <#if log.objectiveResult.statusData?? && log.objectiveResult.statusData.prompt??>
                                            <h5>Prompt</h5>
                                            <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log.objectiveResult.statusData.prompt?string?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                        </#if>
                                    </#if>
                                <#else>
                                    <h5>Raw Log</h5>
                                    <pre style="white-space: pre-wrap; word-break: break-word; background: #f8f9fa; border: 1px solid #ccc; border-radius: 4px; padding: 8px; margin-bottom: 8px;">${log?string?replace('^\\s+|\\s+$', '', 'r')?html}</pre>
                                </#if>
                            </div>
                            <div class="modal-footer">
                                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">
                                    Close
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</div>
