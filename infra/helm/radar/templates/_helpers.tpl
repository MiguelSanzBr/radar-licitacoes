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

{{/* O monolito assume uma replica (agendadores e replay): barra mais de uma ate haver um Deployment por app (ADR-0028). */}}
{{- define "radar.assertSingleReplica" -}}
{{- if and (not .Values.allowMultipleReplicas) (or .Values.autoscaling.enabled (gt (int .Values.replicaCount) 1)) -}}
{{- fail "autoscaling.enabled e replicaCount > 1 nao sao suportados: o monolito roda agendadores e replay que assumem uma unica replica, ate haver um Deployment por app (ADR-0028)" -}}
{{- end -}}
{{- end -}}
