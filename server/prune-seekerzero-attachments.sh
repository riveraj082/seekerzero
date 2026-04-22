#!/bin/bash
# Prune SeekerZero chat attachments older than RETENTION_DAYS. Runs on the
# a0prod host (files live under the single A0 volume). Also removes empty
# per-context dirs so the tree doesn't accumulate dead entries.
TASK_NAME="prune-seekerzero-attachments"
LOG_DIR="/home/a0user/cron-logs"
LOG_FILE="$LOG_DIR/$TASK_NAME.log"
mkdir -p "$LOG_DIR"

RETENTION_DAYS=30
ATTACH_DIR="/home/a0user/agent-zero-data/seekerzero/attachments"

TIMESTAMP=$(TZ="America/New_York" date "+%Y-%m-%d %H:%M:%S ET")
echo "[$TIMESTAMP] Running $TASK_NAME (retention=${RETENTION_DAYS}d)..." >> "$LOG_FILE"

EXIT_CODE=0
if [ -d "$ATTACH_DIR" ]; then
    DELETED_FILES=$(find "$ATTACH_DIR" -type f -mtime +"$RETENTION_DAYS" -print -delete 2>>"$LOG_FILE" | wc -l)
    DELETED_DIRS=$(find "$ATTACH_DIR" -mindepth 1 -type d -empty -print -delete 2>>"$LOG_FILE" | wc -l)
    REMAINING_BYTES=$(du -sb "$ATTACH_DIR" 2>/dev/null | awk '{print $1}')
    echo "Deleted files: $DELETED_FILES" >> "$LOG_FILE"
    echo "Deleted empty dirs: $DELETED_DIRS" >> "$LOG_FILE"
    echo "Remaining bytes: $REMAINING_BYTES" >> "$LOG_FILE"
else
    echo "Attach dir does not exist (nothing to prune): $ATTACH_DIR" >> "$LOG_FILE"
fi
echo "[$TIMESTAMP] Exit code: $EXIT_CODE" >> "$LOG_FILE"
echo "---" >> "$LOG_FILE"

if [ $EXIT_CODE -ne 0 ]; then
    BOT_TOKEN=$(docker exec agent-zero grep TELEGRAM_BOT_TOKEN /a0/usr/workdir/.env 2>/dev/null | cut -d= -f2 | tr -d "\"'")
    CHAT_ID="YOUR_TELEGRAM_CHAT_ID"
    if [ -n "$BOT_TOKEN" ]; then
        MSG="⚠️ Cron task *$TASK_NAME* failed (exit $EXIT_CODE). See $LOG_FILE."
        curl -s -X POST "https://api.telegram.org/bot$BOT_TOKEN/sendMessage" \
            -d chat_id="$CHAT_ID" -d parse_mode="Markdown" -d text="$MSG" > /dev/null 2>&1
    fi
fi

exit $EXIT_CODE
