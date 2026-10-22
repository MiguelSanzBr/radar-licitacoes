{{- define "radar.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "radar.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "radar.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "radar.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- .Values.serviceAccount.name | default (include "radar.fullname" .) -}}
{{- else -}}
{{- .Values.serviceAccount.name | default "default" -}}
{{- end -}}
{{- end -}}

{{- define "radar.secretName" -}}
{{- if .Values.secret.create -}}
{{- include "radar.fullname" . -}}
{{- else -}}
{{- required "secret.existingName e obrigatorio quando secret.create=false" .Values.secret.existingName -}}
{{- end -}}
{{- end -}}

{{/* Replicas minimas efetivas: decide se o PDB existe. */}}
{{- define "radar.minReplicas" -}}
{{- if .Values.autoscaling.enabled -}}{{ .Values.autoscaling.minReplicas }}{{- else -}}{{ .Values.replicaCount }}{{- end -}}
{{- end -}}
