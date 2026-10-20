#!/usr/bin/env python3
"""Offline incident triage: python3 tools/analyze_incident_report.py exported.json [-o triage.md].
No network calls, screenshots, OCR text or personal identifiers are ever collected here.
The input must have been exported manually from the Android operator console.
"""
import argparse
import json
import math
from collections import Counter
from pathlib import Path

CRITICAL = {'COMMAND_UNCERTAIN', 'CAPTURE_PERMISSION_ERROR'}
HIGH = {'OPPONENT_CONTEXT_INVALIDATED', 'OPPONENT_HAND_STORAGE_FAILED', 'COMMAND_REJECTED', 'DECISION_REJECTED', 'SAFETY_BLOCKED',
        'OBSERVATION_UNVERIFIED', 'CAPTURE_FRAME_ERROR', 'DECISION_EXPIRED'}
REVIEW = {'OPPONENT_OBSERVATION_REJECTED', 'OCR_STALE', 'OCR_CONFLICT', 'OCR_ENGINE_ERROR', 'CONTEXT_CHANGED', 'DECISION_MISSING'}
REMEDIES = {
    'OPPONENT_CONTEXT_INVALIDATED': 'Проверить идентичность соперника, окно, эпоху и совпадение событий.',
    'OPPONENT_HAND_STORAGE_FAILED': 'Проверить атомарную запись SQLite и повторить сохранение подтверждённой раздачи.',
    'OPPONENT_OBSERVATION_REJECTED': 'Проверить два независимых подтверждающих кадра и свежесть события.',
    'COMMAND_UNCERTAIN': 'Проверить подтверждение команды по следующему кадру; запрещать повторный жест.',
    'COMMAND_REJECTED': 'Проверить Accessibility, актуальность окна и права на действие.',
    'DECISION_REJECTED': 'Сопоставить допустимые действия, сумму ставки и идентификатор хода.',
    'DECISION_EXPIRED': 'Проверить предел задержки распознавания, ИИ и исполнения.',
    'OBSERVATION_UNVERIFIED': 'Проверить источник ID стола/раздачи и связку кадр–окно.',
    'CAPTURE_FRAME_ERROR': 'Проверить цикл MediaProjection, обработку буфера и потребление памяти.',
    'CAPTURE_PERMISSION_ERROR': 'Проверить выдачу разрешения MediaProjection и переходы жизненного цикла.',
    'SAFETY_BLOCKED': 'Проверить причину fail-closed блокировки и состояние разрешений.',
    'OCR_CONFLICT': 'Сравнить зоны OCR и пороги согласования нескольких кадров.',
    'OCR_STALE': 'Проверить задержки OCR и устаревание кадра.',
    'OCR_ENGINE_ERROR': 'Проверить ML Kit OCR, выдачу результата и обработку исключений.',
    'CONTEXT_CHANGED': 'Проверить переключения окон и границы раздачи.',
    'DECISION_MISSING': 'Проверить доступность стратегии и истечение срока решения.',
}

def analyze(report: dict) -> str:
    if report.get('schema') != 1 or not isinstance(report.get('events'), list):
        raise ValueError('Требуется incident-report JSON со schema=1 и массивом events')
    events = report['events']
    if len(events) > 5000:
        raise ValueError('Слишком большой отчёт — не более 5000 событий')
    allowed_codes = CRITICAL | HIGH | REVIEW | {
        'SESSION_ARMED', 'CAPTURE_STARTED', 'CAPTURE_STOPPED', 'OPERATOR_STOP',
        'COMMAND_REQUESTED', 'COMMAND_DISPATCHED', 'COMMAND_CONFIRMED',
        'GESTURE_COMPLETED_OS', 'FOREGROUND_CHANGED'
    }
    validated = []
    for i, event in enumerate(events):
        if not isinstance(event, dict) or event.get('code') not in allowed_codes:
            raise ValueError(f'Некорректное событие #{i+1}')
        validated.append(event)
    counts = Counter(e['code'] for e in validated)
    latencies = sorted(e['latency_ms'] for e in validated if
                       isinstance(e.get('latency_ms'), int) and 0 <= e['latency_ms'] <= 600_000)
    num_errors = sum(counts[k] for k in CRITICAL | HIGH)
    version = ''.join(c for c in str(report.get('version', 'неизвестна'))[:40]
                      if c.isalnum() or c in '._-')
    out = [
        '# Отчёт по диагностике AI Remote Control GPT',
        '',
        f'Версия: `{version}`. Всего событий: {len(validated)}.',
        f'Критических/высокоприоритетных событий: {num_errors}.',
        '',
        '## Приоритеты доработки',
        ''
    ]
    present = [code for code in CRITICAL | HIGH | REVIEW if counts[code]]
    present.sort(key=lambda code: (0 if code in CRITICAL else 1 if code in HIGH else 2,
                                   -counts[code], code))
    if not present:
        out.append('Ошибки по журналу не обнаружены; это не доказывает корректность приложения.')
    else:
        for code in present:
            tier = 'Критический' if code in CRITICAL else 'Высокий' if code in HIGH else 'Наблюдение'
            out.append(f'- **{tier} · {code}** — {counts[code]} событий. ' +
                       REMEDIES.get(code, 'Проверить причину и повторить диагностику.'))
    out.extend(['', '## Проверка полного цикла', ''])
    for name, code in [('Команды, запрошенные у Android', 'COMMAND_REQUESTED'),
                       ('Жесты, направленные в систему Android', 'COMMAND_DISPATCHED'),
                       ('Жесты, завершённые системой (не подтверждение игры)', 'GESTURE_COMPLETED_OS'),
                       ('Подтверждённые игровые изменения', 'COMMAND_CONFIRMED'),
                       ('Неопределённые результаты', 'COMMAND_UNCERTAIN'),
                       ('Аварийные STOP', 'OPERATOR_STOP')]:
        out.append(f'- {name}: **{counts[code]}**')
    out.extend(['', '## Задержки', ''])
    if latencies:
        p95 = latencies[math.ceil(len(latencies) * .95)-1]
        out.append(f'- Примерная задержка наблюдения: p95 **{p95} мс**, максимум **{latencies[-1]} мс**.')
    else:
        out.append('- Числовые задержки ещё не записаны.')
    failures = [e for e in validated if e['code'] in CRITICAL | HIGH]
    out.extend(['', '## Последние случаи для воспроизведения (без идентификаторов игроков)', ''])
    for event in failures[-10:]:
        parts = [f"событие #{event.get('sequence', '?')}", event['code']]
        for key in ('stage', 'action', 'pot_chips', 'call_chips',
                    'timer_remaining_ms', 'latency_ms', 'frame_sequence'):
            value = event.get(key)
            if isinstance(value, int) or (isinstance(value, str) and
                len(value) <= 20 and value.replace('_', '').isalnum()):
                parts.append(f'{key}={value}')
        out.append('- ' + ', '.join(parts))
    if not failures:
        out.append('Нет событий с критическим/высоким приоритетом.')
    out.extend(['', '## Ограничения', '',
                'Это анализ ограниченного журнала событий, а не воспроизведение видео или доказательство корректных решений.',
                'Номера раздач, содержимое экрана, учётные данные и запросы ИИ не включены.',
                'Отправка отчёта разработчику выполняется оператором вручную.', ''])
    return '\n'.join(out)


def main() -> None:
    p = argparse.ArgumentParser(description='Локальный разбор диагностического отчёта Android')
    p.add_argument('report', type=Path)
    p.add_argument('-o', '--output', type=Path)
    a = p.parse_args()
    content = analyze(json.loads(a.report.read_text(encoding='utf-8')))
    if a.output:
        a.output.write_text(content, encoding='utf-8')
        print(f'Сохранён отчёт: {a.output}')
    else:
        print(content)

if __name__ == '__main__':
    main()
