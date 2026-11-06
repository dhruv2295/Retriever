"""Fit m by conformal risk control on the m440-vt70 calibration split and validate.
Reads the RelevanceReplayTest TSVs in <dir> (see README.md) and prints
markdown tables. Usage: python3 -I eval/relevance-fit.py build/fit"""
import csv, math, sys, json
from collections import defaultdict

D = sys.argv[1]
ALPHA, LISTED, GATE_FS, GATE_REC = 0.10, 20, 0.12, 0.80
GROUP = {'IMAGE': 'photo', 'TEXT': 'text'}
CLEAN = {'heldout_target', 'absent_domain'}


def load(name):
    out = []
    for r in csv.DictReader(open(f'{D}/{name}.tsv'), delimiter='\t'):
        f = lambda k: float(r[k]) if r[k] != '' else float('nan')
        i = lambda k: int(r[k]) if r[k] != '' else None
        out.append(dict(q=r['query'], ds=r['dataset'], lang=r['lang'], split=r['split'], kind=r['kind'],
                        con=r['construction'], g=GROUP.get(r['group'], r['group']), n=int(r['n']),
                        thin=r['median'] == '', right=r['right_file'], re=f('right_excess'), rr=i('right_file_rank'),
                        wrong=r['wrong_file'], we=f('wrong_excess'), wr=i('wrong_file_rank')))
    return out


def strong(e, thin, m):  # a thin group (n < minRows) calls every hit strong
    return thin or e >= m


def matches(rows):  # one entry per match query: the row holding its right file
    return [r for r in rows if r['kind'] == 'match' and r['right']]


def fit(rows):
    ex = sorted(r['re'] for r in matches(rows) if r['split'] == 'calibration' and r['rr'] <= LISTED)
    n = len(ex)
    k = math.floor(ALPHA * (n + 1))  # CRC with loss 1{excess < m}: the k-th smallest calibration excess
    return ex[k - 1], n


def metrics(rows, m, sel=lambda r: True):
    """Per group: strong recall over listed right files, recall over all, clean and noisy false-strong."""
    res = defaultdict(lambda: defaultdict(int))
    for r in matches(rows):
        if not sel(r): continue
        s = res[r['g']]
        s['match'] += 1
        if r['rr'] <= LISTED:
            s['listed'] += 1
            s['strong'] += strong(r['re'], r['thin'], m)
            s['thin'] += r['thin']
    anyq = defaultdict(lambda: [0, 0])  # construction class -> [queries, with a strong hit in any group]
    hit = {}
    for r in rows:
        if r['kind'] != 'nomatch' or not sel(r): continue
        cls = 'clean' if r['con'] in CLEAN else 'noisy'
        s = res[r['g']]
        s[cls] += 1
        fs = r['wrong'] != '' and r['wr'] <= LISTED and strong(r['we'], r['thin'], m)
        s[cls + '_fs'] += fs
        s[cls + '_thin'] += r['thin']
        hit[(r['q'], cls)] = hit.get((r['q'], cls), False) or fs
    for (q, cls), fs in hit.items():
        anyq[cls][0] += 1
        anyq[cls][1] += fs
    return res, anyq


LABEL = {True: 'pass', False: 'FAIL', 'thin': 'abstains (n < minRows)', None: 'no data'}


def rate(a, b):
    return float('nan') if b == 0 else a / b


def fmt(a, b):
    return '-' if b == 0 else f'{a / b:.3f} ({a}/{b})'


def gate(s):
    rec = rate(s['strong'], s['listed'])
    fs = rate(s['clean_fs'], s['clean'])
    return (rec >= GATE_REC) and (fs <= GATE_FS)


def table(title, configs, m_of, sel_of=lambda c: (lambda r: True)):
    print(f'\n#### {title}\n')
    print('| config | group | m | strong recall, listed | strong recall, all | false-strong, clean no-match | false-strong, noisy (held-out-photo captions) | thin-group share | gate |')
    print('|---|---|---|---|---|---|---|---|---|')
    verdict = {}
    for c in configs:
        rows = DATA[c[0]]
        m = m_of(c)
        res, anyq = metrics(rows, m, sel_of(c))
        for g in ['text', 'photo']:
            s = res[g]
            thin = rate(s['thin'] + s['clean_thin'] + s['noisy_thin'], s['listed'] + s['clean'] + s['noisy'])
            ok = gate(s) if thin == 0 else 'thin'
            if s['listed'] == 0 or s['clean'] == 0: ok = None
            verdict[(c, g)] = ok
            print(f"| {c[1]} | {g} | {m:.3f} | {fmt(s['strong'], s['listed'])} | {fmt(s['strong'], s['match'])} | "
                  f"{fmt(s['clean_fs'], s['clean'])} | {fmt(s['noisy_fs'], s['noisy'])} | {thin:.2f} | "
                  f"{LABEL[ok]} |")
        print(f"| {c[1]} | any group (query level) | {m:.3f} | | | {fmt(anyq['clean'][1], anyq['clean'][0])} | {fmt(anyq['noisy'][1], anyq['noisy'][0])} | | |")
    return verdict


DATA = {k: load(k) for k in ['m440-vt70', 'm740-vt70', 'm440-vt140', 'm440-vt70-s1000', 'm440-vt70-s5000', 'm440-vt70-s1000-minrows1']}
M, NCAL = fit(DATA['m440-vt70'])
print(f'### Fit\n\nm = {M:.4f} (CRC, alpha = {ALPHA}, k = floor(alpha (n + 1)) = {math.floor(ALPHA * (NCAL + 1))}-th smallest of n = {NCAL} '
      f'listed calibration match excesses, M440/92dcbea1/vt70)\n')
print('| calibration diagnostic | group | lang | FNR (right file listed but weak) |\n|---|---|---|---|')
cal = [r for r in matches(DATA['m440-vt70']) if r['split'] == 'calibration' and r['rr'] <= LISTED]
for g in ['text', 'photo']:
    for lang in ['en', 'hi', None]:
        xs = [r for r in cal if r['g'] == g and (lang is None or r['lang'] == lang)]
        miss = sum(not strong(r['re'], r['thin'], M) for r in xs)
        print(f"| m440-vt70 calibration | {g} | {lang or 'all'} | {fmt(miss, len(xs))} |")

test = lambda c: (lambda r: r['split'] == 'test')
V = {}
V.update(table('Held-out test split and transfer (m fitted on m440-vt70 calibration, unchanged)',
               [('m440-vt70', 'M440 vt70 (fit)'), ('m740-vt70', 'M740 vt70'), ('m440-vt140', 'M440 vt140')], lambda c: M, test))
for lang in ['en', 'hi']:
    table(f'Per language: {lang} (test split)', [('m440-vt70', 'M440 vt70'), ('m440-vt140', 'M440 vt140')],
          lambda c: M, lambda c, lang=lang: (lambda r: r['split'] == 'test' and r['lang'] == lang))

# Leave one dataset out: fit on the calibration split of the other two, test on the held-out one's test split.
lodo_m = {}
for out in ['xm3600', 'scifact', 'miracl-hi']:
    lodo_m[out], n = fit([r for r in DATA['m440-vt70'] if r['ds'] != out])
    print(f'\nLODO without {out}: m = {lodo_m[out]:.4f} from n = {n}')
V.update(table('Leave one dataset out (M440 vt70; test split of the left-out dataset only)',
               [('m440-vt70', f'without {d}', d) for d in ['xm3600', 'scifact', 'miracl-hi']],
               lambda c: lodo_m[c[2]], lambda c: (lambda r: r['split'] == 'test' and r['ds'] == c[2])))

V.update(table('Library-size sweep (M440 vt70, test split, same m)',
               [('m440-vt70-s1000', '1k files'), ('m440-vt70-s5000', '5k files'), ('m440-vt70', 'full 11,983 files')], lambda c: M, test))
table('1k library with minRows = 1 (diagnostic only: would the rule hold on a ~150-row photo group?)',
      [('m440-vt70-s1000-minrows1', '1k files, minRows 1')], lambda c: M, test)

# Transfer check from section 4.4: test FNR inside the 95% binomial interval around alpha.
print('\n#### Transfer: test FNR against the 95% interval of alpha\n\n| config | group | FNR | interval | inside |\n|---|---|---|---|---|')
for k in ['m440-vt70', 'm740-vt70', 'm440-vt140']:
    res, _ = metrics(DATA[k], M, lambda r: r['split'] == 'test')
    for g in ['text', 'photo']:
        s = res[g]
        n = s['listed']
        fnr = 1 - s['strong'] / n
        h = 1.96 * math.sqrt(ALPHA * (1 - ALPHA) / n)
        print(f'| {k} | {g} | {fnr:.3f} (n {n}) | {ALPHA - h:.3f}-{ALPHA + h:.3f} | {"yes" if ALPHA - h <= fnr <= ALPHA + h else "no"} |')

print('\n#### Gate per group\n')
for g in ['text', 'photo']:
    vs = {c[1]: v for (c, gg), v in V.items() if gg == g}
    print(f'- {g}: ' + ', '.join(f'{k}: {LABEL[v]}' for k, v in vs.items()))
json.dump({'m': M, 'n_cal': NCAL, 'lodo': lodo_m, 'gate': {f'{c[1]}|{g}': v for (c, g), v in V.items()}}, open(f'{D}/fit-summary.json', 'w'), indent=1)


def auroc(pos, neg):
    allv = sorted([(v, 1) for v in pos] + [(v, 0) for v in neg])
    rank_sum, i = 0.0, 0
    while i < len(allv):  # average ranks over ties
        j = i
        while j < len(allv) and allv[j][0] == allv[i][0]: j += 1
        rank_sum += sum(1 for k in range(i, j) if allv[k][1]) * (i + j + 1) / 2
        i = j
    return (rank_sum - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))


print('\n#### Separation on the test split, threshold-free, and the best any single m could do\n')
print('AUROC: right file (listed match) excess vs best listed wrong file (no-match) excess, per hit group. '
      '"Best m on test": the m that maximises strong recall subject to false-strong <= 0.12, chosen on the test split itself (an upper bound, not a fit).\n')
print('| config | group | no-match set | AUROC | best m on test | recall there | false-strong there |\n|---|---|---|---|---|---|---|')
for k in ['m440-vt70', 'm440-vt140', 'm440-vt70-s5000', 'm440-vt70-s1000-minrows1']:
    rows = [r for r in DATA[k] if r['split'] == 'test']
    for g in ['text', 'photo']:
        for lang in (['all', 'en', 'hi'] if k == 'm440-vt70' else ['all']):
            pos = [r['re'] for r in matches(rows) if r['g'] == g and r['rr'] <= LISTED and lang in ('all', r['lang'])]
            for cls, cons in [('clean', CLEAN), ('noisy', {'heldout_image'})]:
                neg_all = [r for r in rows if r['kind'] == 'nomatch' and r['g'] == g and r['con'] in cons and lang in ('all', r['lang'])]
                neg = [r['we'] for r in neg_all if r['wrong'] and r['wr'] <= LISTED]
                if not pos or not neg_all: continue
                best = None
                for m in sorted(set(pos)):
                    fs = sum(v >= m for v in neg) / len(neg_all)
                    rec = sum(v >= m for v in pos) / len(pos)
                    if fs <= GATE_FS and (best is None or rec > best[1]): best = (m, rec, fs)
                b = f'{best[0]:.2f} | {best[1]:.3f} | {best[2]:.3f}' if best else 'none | - | -'
                print(f'| {k} | {g} | {lang}, {cls} ({len(neg_all)}) | {auroc(pos, neg) if neg else float("nan"):.3f} | {b} |')
