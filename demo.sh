#!/usr/bin/env bash
# Offline demo (default profile). Start the app first:  mvn spring-boot:run
set -euo pipefail
API=http://localhost:8080/api

echo "== Tenants"; curl -s $API/tenants; echo
echo "== Before: alice's inbox (smallclinic)"
curl -s $API/tenants/smallclinic/mailboxes/alice@smallclinic.example/inbox; echo

echo "== Guardrail: empty criteria is refused"
curl -s -X POST $API/remediations -H 'Content-Type: application/json' -d '{}'; echo

echo "== Pull the phishing campaign from every tenant"
JOB=$(curl -s -X POST $API/remediations -H 'Content-Type: application/json' \
  -d '{"sender":"billing@micros0ft-support.example","subjectContains":"invoice overdue"}' \
  | sed -E 's/.*"jobId":"([^"]+)".*/\1/')
echo "jobId=$JOB"

sleep 1
echo "== After 1s: smallclinic is already done, bigbank still working (fairness)"
curl -s $API/remediations/$JOB; echo
sleep 4
echo "== After 5s: everything done"
curl -s $API/remediations/$JOB; echo

echo "== Detail for ONE tenant only (isolation)"
curl -s $API/remediations/$JOB/tenants/smallclinic; echo
echo "== After: alice's inbox no longer has the phish"
curl -s $API/tenants/smallclinic/mailboxes/alice@smallclinic.example/inbox; echo
echo "== Audit trail (smallclinic only)"
curl -s $API/tenants/smallclinic/audit; echo

echo "== Undo (false positive) -> messages go back to the inbox"
curl -s -X POST $API/remediations/$JOB/restore; echo
sleep 4
curl -s $API/tenants/smallclinic/mailboxes/alice@smallclinic.example/inbox; echo

# ---------------------------------------------------------------------------------------------
# Alert-driven pipeline: threat alerts per threat -> intake -> dedup -> state -> decision -> action
# ---------------------------------------------------------------------------------------------
MSG='<phish-42@micros0ft-support.example>'
alert() { # $1 threatId  $2 status
  cat <<JSON
[{"tenantId":"smallclinic","threatId":"$1","status":"$2","messages":[{"messageId":"$MSG",
  "sender":"billing@micros0ft-support.example","subject":"Urgent: invoice overdue - action required",
  "recipients":["all-staff@smallclinic.example"]}]}]
JSON
}
post_alert() { curl -s -X POST $API/alerts -H 'Content-Type: application/json' -d "$(alert "$1" "$2")"; echo; }

echo "== Two threats on one message (URL + attachment), sent to a nested distribution list"
post_alert url-1 ACTIVE
post_alert att-1 ACTIVE
sleep 2
curl -s $API/tenants/smallclinic/messages; echo

echo "== Same verdict again: deduplicated"
post_alert url-1 ACTIVE

echo "== URL cleared, attachment still bad: message STAYS quarantined"
post_alert url-1 CLEARED
sleep 1
curl -s $API/tenants/smallclinic/messages; echo

echo "== Attachment is a false positive: every threat cleared -> restore"
post_alert att-1 FALSE_POSITIVE
sleep 2
curl -s $API/tenants/smallclinic/messages; echo

echo "== Incidents, metrics, dead letters, routing table"
curl -s $API/tenants/smallclinic/incidents; echo
curl -s $API/tenants/smallclinic/metrics; echo
curl -s $API/tenants/smallclinic/dead-letters; echo
curl -s $API/routing; echo
echo "Admin UI: http://localhost:8080/admin.html"
