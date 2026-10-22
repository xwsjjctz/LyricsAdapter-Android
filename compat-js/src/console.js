import { format } from 'util';

export function createConsole(log) {
  const emit = (level) => (...args) => log(level, format(...args));
  const counters = new Map();
  const timers = new Map();

  return {
    debug: emit('debug'),
    trace: emit('debug'),
    log: emit('info'),
    info: emit('info'),
    warn: emit('warn'),
    error: emit('error'),
    dir: (value) => log('info', format('%O', value)),
    table: emit('info'),
    group: emit('info'),
    groupCollapsed: emit('info'),
    groupEnd: () => {},
    assert: (condition, ...args) => {
      if (!condition) log('error', format('Assertion failed', ...args));
    },
    count: (label = 'default') => {
      const count = (counters.get(label) ?? 0) + 1;
      counters.set(label, count);
      log('info', `${label}: ${count}`);
    },
    countReset: (label = 'default') => counters.delete(label),
    time: (label = 'default') => timers.set(label, Date.now()),
    timeLog: (label = 'default', ...args) => log('info', format(`${label}: ${Date.now() - (timers.get(label) ?? Date.now())}ms`, ...args)),
    timeEnd: (label = 'default') => {
      log('info', `${label}: ${Date.now() - (timers.get(label) ?? Date.now())}ms`);
      timers.delete(label);
    },
  };
}
