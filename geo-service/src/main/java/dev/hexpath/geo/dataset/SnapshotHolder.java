package dev.hexpath.geo.dataset;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SnapshotHolder {
    public Optional<DatasetSnapshot> current() {
        throw new UnsupportedOperationException("not implemented");
    }

    public void swap(DatasetSnapshot next) {
        throw new UnsupportedOperationException("not implemented");
    }
}
