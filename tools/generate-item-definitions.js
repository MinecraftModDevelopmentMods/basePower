// Keep native item definitions beside the unchanged, shared block/item models.
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..', 'src/main/resources/assets/basepower');
const source = path.join(root, 'models/item');
const target = path.join(root, 'items');
const check = process.argv.includes('--check');
const names = fs.readdirSync(source).filter(name => name.endsWith('.json')).sort();
if (!names.length) throw new Error('No Power item models found');
if (!check) fs.mkdirSync(target, {recursive: true});
for (const name of names) {
  const text = JSON.stringify({model: {type: 'minecraft:model', model: 'basepower:item/' + name.slice(0, -5)}}, null, 2) + '\n';
  const file = path.join(target, name);
  if (check) {
    if (!fs.existsSync(file) || fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') !== text)
      throw new Error('Missing or stale native item definition: ' + file);
  } else fs.writeFileSync(file, text);
}
for (const name of fs.readdirSync(target)) {
  if (name.endsWith('.json') && !names.includes(name)) throw new Error('Orphan native item definition: ' + name);
}
console.log(names.length + ' Power native item definitions ' + (check ? 'verified' : 'generated'));
