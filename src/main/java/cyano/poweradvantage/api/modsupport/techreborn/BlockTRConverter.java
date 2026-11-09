package cyano.poweradvantage.api.modsupport.techreborn;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.modsupport.rf.BlockRFConverter;

/** Historical Tech Reborn-labelled converter block, backed by Forge Energy. */
public class BlockTRConverter extends BlockRFConverter {
    @SuppressWarnings("unchecked")
    public BlockTRConverter(Properties properties, ConduitType type,
            Class<? extends TileEntityTRConverter> tileEntityClass) {
        super(properties, type,
                (Class<? extends cyano.poweradvantage.api.modsupport.rf.TileEntityRFConverter>) tileEntityClass);
    }
}
