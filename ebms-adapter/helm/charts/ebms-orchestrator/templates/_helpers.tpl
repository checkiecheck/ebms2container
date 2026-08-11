{{/*
Expand the name of the chart.
*/}}
{{- define "ebms-orchestrator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Volledig gekwalificeerde app-naam (release + chart).
*/}}
{{- define "ebms-orchestrator.fullname" -}}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Gemeenschappelijke labels voor alle resources.
*/}}
{{- define "ebms-orchestrator.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
app.kubernetes.io/name: {{ include "ebms-orchestrator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/component: orchestrator
app.kubernetes.io/part-of: ebms-adapter
{{- end }}

{{/*
Selector labels (stabiel – niet wijzigen na uitrol).
*/}}
{{- define "ebms-orchestrator.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ebms-orchestrator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
