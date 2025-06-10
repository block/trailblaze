<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Logs Viewer</title>
    <link href="https://getbootstrap.com/docs/5.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <script type="text/javascript">
        drawScreenshotCircle = function (log_index, deviceWidth, deviceHeight, clickX, clickY) {
            document.addEventListener('DOMContentLoaded', function () {
                var img = document.getElementById('screenshot-' + log_index);
                if (img.complete) { // Ensure image is loaded
                    createCircle(img);
                } else {
                    img.onload = function () {
                        createCircle(img);
                    };
                }

                function createCircle(img) {
                    let realImageWidth = img.naturalWidth;
                    let realImageHeight = img.naturalHeight;
                    let cssDisplayedWidth = img.clientWidth;
                    let cssDisplayedHeight = img.clientHeight;

                    console.log("Natural Size: " + realImageWidth + "x" + realImageHeight);
                    console.log("Displayed Size: " + cssDisplayedWidth + "x" + cssDisplayedHeight);
                    console.log("Device Size: " + deviceWidth + "x" + deviceHeight);

                    let deviceToScreenshotScaleX = deviceWidth / realImageWidth;
                    let deviceToScreenshotScaleY = deviceHeight / realImageHeight;
                    console.log("deviceToScreenshotScaleY : " + deviceToScreenshotScaleX + "x" + deviceToScreenshotScaleY);

                    let scaleX = cssDisplayedWidth / realImageWidth;
                    let scaleY = cssDisplayedHeight / realImageHeight;
                    console.log("scaleX : " + scaleX + "x" + scaleY);

                    let circleX = clickX * scaleX / deviceToScreenshotScaleX;
                    let circleY = clickY * scaleY / deviceToScreenshotScaleY;

                    let radius = 20;
                    let borderWidth = 4;
                    let offset = radius / 2;

                    var circle = document.createElement('div');
                    circle.style.position = 'absolute';
                    circle.style.left = (circleX - offset) + 'px';
                    circle.style.top = (circleY - offset) + 'px';
                    circle.style.width = radius + 'px';
                    circle.style.height = radius + 'px';
                    circle.style.backgroundColor = 'red';
                    circle.style.borderRadius = '50%';
                    circle.style.border = borderWidth + 'px solid white';
                    img.parentElement.appendChild(circle);
                }

                // Recalculate and redraw the circle on window resize
                window.addEventListener('resize', function () {
                    const existingCircle = img.parentElement.querySelector('div');
                    if (existingCircle) {
                        existingCircle.remove();
                    }
                    createCircle(img);
                });
            })
        };
    </script>
</head>
<body class="bg-light">
<div class="container-fluid p-3 bg-white rounded-lg shadow-sm">
    <#include "*/header.ftl">
    <div class="row">
        <div class="col-md-6">
            <h1 class="display-4 font-weight-bold">Trailblaze Logs</h1>
        </div>
        <div class="col-md-6">
            <a href="/recording/json/${session}">
                <button type="button" class="btn btn-primary me-3">
                    JSON and Kotlin Recording
                </button>
            </a>
            <span> </span>
            <a href="/recording/yaml/${session}">
                <button type="button" class="btn btn-primary me-3">
                    Maestro YAML Recording
                </button>
            </a>
            <span> </span>
            <a href="/llm/${session}">
                <button type="button" class="btn btn-primary me-3">
                    LLM Messages
                </button>
            </a>
            <span> </span>
        </div>
    </div>
    <#if status??>
        <div class="row">
            <div class="col">
                <h2 class="display-6">
                    <span class="font-weight-bold">${statusMessage}</span>
                    <#if inProgress == true>
                        <div class="spinner-border" role="status">
                            <span class="sr-only"></span>
                        </div>
                        <span> Elapsed Time: <span id="elapsed_time"></span> seconds</span>
                    </#if>
                </h2>
                <h4>Prompt</h4>
                <pre>${status.prompt}</pre>
            </div>
        </div>
    </#if>
    <div class="album py-5">
        <div class="row row-cols-1 row-cols-sm-3 row-cols-md-4 row-cols-lg-6">
            <#if logs?size == 0>
                <p>No logs available for Session "${session}"</p>
            </#if>
            <#list logs as log>
                <div class="col" style="margin-bottom:10px;">
                    <div class="card shadow-sm">
                        <div style="position: relative;">
                            <#assign logColors = {
                            "MAESTRO_DRIVER": "lightblue",
                            "MAESTRO_COMMAND": "lightpink",
                            "TRAILBLAZE_COMMAND": "lightyellow",
                            "LLM_REQUEST": "lightgreen",
                            "AGENT_TASK_STATUS": "lightgray",
                            "SESSION_STATUS": "lightseagreen"
                            }>
                            <#assign logColor = logColors[log.type]>
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
                                <#if log.type == "MAESTRO_COMMAND">
                                    <pre class="small">${log.asMaestroYaml()?html}</pre>
                                <#elseif log.type == "TRAILBLAZE_COMMAND">
                                    <pre class="small">${log.asCommandJson()?html}</pre>
                                <#elseif log.type == "AGENT_TASK_STATUS">
                                    <h6 class="font-weight-bold">${log.agentTaskStatus.class.simpleName}</h6>
                                    <pre class="small">${log.agentTaskStatus.statusData.prompt?html}</pre>
                                <#elseif log.type == "SESSION_STATUS">
                                    <p>${log.sessionStatus?html}</p>
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
                            <p class="card-text">
                                <strong>Time Elapsed:</strong>
                                <span>${(log.timestamp - logs[0].timestamp)?number_to_date?string("mm:ss")}</span>
                            </p>
                            <div class=" text-center">
                                <button type="button" class="btn btn-primary" data-bs-toggle="modal"
                                        data-bs-target="#logModal${log_index}">
                                    View Details
                                </button>
                            </div>
                            <!-- Modal -->
                            <div class="modal fade" id="logModal${log_index}" tabindex="-1"
                                 aria-labelledby="logModalLabel${log_index}" aria-hidden="true">
                                <div class="modal-dialog modal-lg">
                                    <div class="modal-content">
                                        <div class="modal-header">
                                            <h5 class="modal-title" id="logModalLabel${log_index}">Log Details</h5>
                                            <button type="button" class="close" data-bs-dismiss="modal"
                                                    aria-label="Close">
                                                <span aria-hidden="true">&times;</span>
                                            </button>
                                        </div>
                                        <div class="modal-body">
                                            <#if log.type == 'MAESTRO_DRIVER'>
                                                <pre>${log.action.class.simpleName}</pre>
                                                <pre>${log.debugString()?html}</pre>
                                            <#elseif log.type == 'LLM_REQUEST'>
                                                <h5>LLM Response</h5>
                                                <pre>${log.llmResponse?html}</pre>
                                                <h5>Request Duration</h5>
                                                <pre>${(log.duration)?number_to_date?string("mm:ss")}</pre>
                                                <#if log.llmMessage??>
                                                    <h5>LLM Message</h5>
                                                    <pre>${log.llmMessage?html}</pre>
                                                </#if>

                                                <!-- Collapsible Chat History -->
                                                <button class="btn btn-outline-secondary mb-2" type="button" data-bs-toggle="collapse" data-bs-target="#chatHistory${log_index}" aria-expanded="false" aria-controls="chatHistory${log_index}">
                                                    Show Full Chat History
                                                </button>
                                                <div class="collapse" id="chatHistory${log_index}">
                                                    <div class="card card-body" style="max-height: 400px; overflow-y: auto;">
                                                        <#if log.llmMessages?? && (log.llmMessages?size > 0)>
                                                            <#list log.llmMessages as message>
                                                                <#if message.message??>
                                                                    <#-- Remove base64 screenshot blocks from message -->
                                                                    <#assign cleanMessage = message.message?replace('data:image/png;base64,[A-Za-z0-9+/=]+', '[screenshot removed]', 'r')>
                                                                    <div class="mb-2">
                                                                        <span class="badge bg-secondary">${message.role?cap_first}</span>
                                                                        <div class="border rounded p-2 mt-1" style="background-color: <#if message.role == 'user'>#e3f2fd<#elseif message.role == 'assistant'>#f1f8e9<#else>#f8bbd0</#if>;">
                                                                            <pre style="white-space: pre-wrap; word-break: break-word; margin: 0;">${cleanMessage?html}</pre>
                                                                        </div>
                                                                    </div>
                                                                </#if>
                                                            </#list>
                                                        <#else>
                                                            <div class="text-muted">No chat history available.</div>
                                                        </#if>
                                                    </div>
                                                </div>
                                                <h5>Actions Returned</h5>
                                                <#list log.actions as action>
                                                    <pre>${action?html}</pre>
                                                </#list>
                                            <#else>
                                                <pre>${log?html}</pre>
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
            </#list>
        </div>
    </div>
    <br/>
    <br/>
    <#if llmUsageSummary??>
        <h5>Llm Usage Summary</h5>
        <pre>${llmUsageSummary}</pre>
    </#if>
</div>
<script src="https://getbootstrap.com/docs/5.3/dist/js/bootstrap.min.js"></script>
<script type="text/javascript">
    document.addEventListener("DOMContentLoaded", function () {
        const id = "${session?json_string}";
        const socket = new WebSocket("ws://" + window.location.host + "/updates?id=" + id);

        socket.addEventListener('message', function (event) {
            console.log('Update received, reloading page...');
            window.location.reload();
        });

        socket.addEventListener('error', function (error) {
            console.error('WebSocket error:', error);
        });

        socket.addEventListener('close', function (event) {
            console.log('WebSocket closed:', event);
        });
    });
</script>
<#if status??>
    <script type="text/javascript">
        const startTimeMs = ${status.taskStartTime?c};

        const displayElapsedSeconds = () => {
            const nowMs = Date.now();
            const elapsedMs = nowMs - startTimeMs;
            const elapsedSeconds = Math.floor(elapsedMs / 1000);

            const elapsedTimeElement = document.getElementById('elapsed_time');
            if (elapsedTimeElement) {
                elapsedTimeElement.textContent = elapsedSeconds.toString();
            }
        };

        displayElapsedSeconds();

        // Update every second
        setInterval(displayElapsedSeconds, 1000);
    </script>
</#if>
</body>
</html>
